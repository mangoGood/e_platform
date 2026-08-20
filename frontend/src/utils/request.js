import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import router from '@/router'
import { waitInQueue, QueueError } from '@/utils/queue'

/**
 * 全站唯一的 axios 实例。
 *
 * <h3>后端的错误契约</h3>
 * 网关与各服务返回<b>真实 HTTP 状态码</b>：401 / 403 / 404 / 409 / 400 / 408 / 503。
 * 中文文案在 `error.response.data.message`。
 * 只有 `code=500` 这类「软失败」仍走 HTTP 200 + body.code != 200。
 *
 * <h3>本文件必须守住的契约</h3>
 * success 分支返回的是 `response.data`（整个 Result 包装体），
 * 因此全站调用方写的都是 `res.data.xxx`。
 * <b>不要把它扁平化成 `response.data.data`</b> —— 那需要同步改掉每一个调用点，
 * 漏一个就是运行时 undefined，收益为零、风险极高。
 */

const BASE_URL = '/api'
const DEFAULT_TIMEOUT = 15000

const HTTP_OK = 200
const HTTP_ACCEPTED = 202
const HTTP_UNAUTHORIZED = 401

/** 同一个下单请求最多允许被重新排队几次，防止服务端持续 202 造成死循环。 */
const MAX_QUEUE_ROUNDS = 3

/**
 * 状态码兜底文案。
 *
 * 只在后端没给 message 时使用。永远不把 axios 自动生成的
 * "Request failed with status code 401" 这类英文串抛给用户。
 */
const HTTP_FALLBACK_MESSAGES = {
  400: '请求参数有误，请检查后重试',
  401: '登录已过期，请重新登录',
  403: '没有权限执行该操作',
  404: '请求的资源不存在',
  405: '请求方式不被支持',
  408: '请求超时，请稍后重试',
  409: '操作冲突，请刷新后重试',
  422: '提交的内容不合法',
  429: '操作过于频繁，请稍后再试',
  500: '服务器开小差了，请稍后再试',
  502: '服务暂不可用，请稍后再试',
  503: '当前访问人数过多，请稍后再试',
  504: '服务响应超时，请稍后再试'
}

const request = axios.create({
  baseURL: BASE_URL,
  timeout: DEFAULT_TIMEOUT
})

/**
 * 刷新 token 专用的裸实例。
 *
 * 不挂任何拦截器 —— 这是「防止递归刷新」的第一道也是最彻底的一道保险：
 * 即便 /user/refresh 自己返回 401，也不会再次触发刷新逻辑。
 */
const rawHttp = axios.create({
  baseURL: BASE_URL,
  timeout: DEFAULT_TIMEOUT
})

// -----------------------------------------------------------------------------
// 提示去重
//
// 并发请求同时失败时（ProductDetail 的 onMounted 就会并发两个请求），
// 相同文案会连弹多次。这里对相同文案做短窗口去重。
// -----------------------------------------------------------------------------

/** 最近展示过的文案 -> 时间戳。 */
const recentMessages = new Map()

/** 去重窗口（毫秒）。 */
const MESSAGE_DEDUPE_MS = 1200

/**
 * 弹出错误提示（同文案短时间内只弹一次）。
 *
 * @param {string} message 中文提示
 * @returns {void}
 */
function notifyError(message) {
  if (!message) {
    return
  }
  const now = Date.now()
  const last = recentMessages.get(message)
  if (last && now - last < MESSAGE_DEDUPE_MS) {
    return
  }
  recentMessages.set(message, now)
  // 顺手清理过期记录，避免 Map 无限增长
  for (const [key, at] of recentMessages) {
    if (now - at > MESSAGE_DEDUPE_MS) {
      recentMessages.delete(key)
    }
  }
  ElMessage.error(message)
}

/**
 * 从错误对象里解析出面向用户的中文文案。
 *
 * 优先级：后端 message > 状态码兜底 > 网络异常 > 通用兜底。
 *
 * @param {any} error axios 错误对象
 * @returns {string} 中文提示
 */
function resolveErrorMessage(error) {
  const backendMessage = error?.response?.data?.message
  if (typeof backendMessage === 'string' && backendMessage.trim()) {
    return backendMessage
  }

  const status = error?.response?.status
  if (status && HTTP_FALLBACK_MESSAGES[status]) {
    return HTTP_FALLBACK_MESSAGES[status]
  }
  if (status) {
    return `请求失败（${status}），请稍后重试`
  }

  if (error?.code === 'ECONNABORTED' || /timeout/i.test(error?.message || '')) {
    return '请求超时，请稍后重试'
  }
  return '网络异常，请检查连接'
}

/**
 * 给请求配置设置请求头，兼容 axios 1.x 的 AxiosHeaders 与普通对象两种形态。
 *
 * @param {object} config axios 请求配置
 * @param {string} name 头名
 * @param {string} value 头值
 * @returns {void}
 */
function setHeader(config, name, value) {
  if (config.headers && typeof config.headers.set === 'function') {
    config.headers.set(name, value)
    return
  }
  config.headers = { ...(config.headers || {}), [name]: value }
}

// -----------------------------------------------------------------------------
// 单飞刷新（single-flight refresh）
//
// 后端的 refresh 是「轮换式」的：每成功刷新一次，旧的 refresh token 立即作废。
// 因此 N 个并发 401 如果各自去打 /user/refresh，就会互相把对方的凭据作废，
// 结果是用户被直接踢下线 —— 比不做刷新还糟。
//
// 解法：全模块只允许存在一个「进行中的刷新 Promise」。
// 第一个 401 发起刷新，后续 401 一律 await 同一个 Promise（天然形成挂起队列），
// 拿到新 token 后各自重放自己的原请求。
// -----------------------------------------------------------------------------

/** 进行中的刷新 Promise；空闲时为 null。 */
let refreshPromise = null

/** 「登录已过期」提示是否已弹过，避免 N 个并发请求弹 N 次。 */
let logoutNoticeShown = false

/**
 * 发起（或复用）一次 token 刷新。
 *
 * @returns {Promise<string>} 新的 access token
 */
function refreshAccessToken() {
  if (refreshPromise) {
    // 已有刷新在途：直接排队等它，不重复发起。
    return refreshPromise
  }

  const userStore = useUserStore()
  const currentRefreshToken = userStore.refreshToken

  if (!currentRefreshToken) {
    return Promise.reject(new Error('NO_REFRESH_TOKEN'))
  }

  refreshPromise = rawHttp
    .post('/user/refresh', { refreshToken: currentRefreshToken })
    .then((response) => {
      const body = response?.data
      const pair = body?.data
      if (!body || body.code !== HTTP_OK || !pair?.token) {
        throw new Error('REFRESH_REJECTED')
      }
      // 刷新响应只含 token / refreshToken / expiresIn，不含 roles / perms，
      // 因此这里只更新令牌，角色权限沿用登录时缓存的那份。
      userStore.applyTokenPair(pair)
      return pair.token
    })
    .finally(() => {
      // 无论成败都要释放，否则下一次过期将永远拿到这个已 settle 的 Promise。
      refreshPromise = null
    })

  return refreshPromise
}

/**
 * 清理登录态并跳转登录页。
 *
 * @param {any} error 原始错误，原样透传给调用方
 * @returns {Promise<never>}
 */
function forceLogout(error) {
  const userStore = useUserStore()
  userStore.clearAuth()

  if (!logoutNoticeShown) {
    logoutNoticeShown = true
    ElMessage.error('登录已过期，请重新登录')
    setTimeout(() => {
      logoutNoticeShown = false
    }, MESSAGE_DEDUPE_MS * 2)
  }

  const current = router.currentRoute.value
  if (current.name !== 'Login') {
    router.push({ name: 'Login', query: { redirect: current.fullPath } })
  }
  return Promise.reject(error)
}

/**
 * 处理 401：先尝试静默刷新并重放，刷新不了才登出。
 *
 * @param {any} error axios 错误对象
 * @returns {Promise<any>} 重放后的响应，或 reject
 */
async function handleUnauthorized(error) {
  const config = error.config

  // 无 config（极端情况）、已重放过、或调用方显式声明跳过刷新 —— 一律不再刷新。
  // 这是「防递归」的第二道保险。
  if (!config || config.__isAuthRetry || config.skipAuthRefresh) {
    return forceLogout(error)
  }

  const userStore = useUserStore()
  if (!userStore.refreshToken) {
    // 从来没登录过的游客访问受保护接口：不该弹「登录已过期」，
    // 按后端文案提示并引导登录即可。
    if (!userStore.token) {
      notifyError(resolveErrorMessage(error))
      const current = router.currentRoute.value
      if (current.name !== 'Login') {
        router.push({ name: 'Login', query: { redirect: current.fullPath } })
      }
      return Promise.reject(error)
    }
    return forceLogout(error)
  }

  try {
    const newToken = await refreshAccessToken()
    config.__isAuthRetry = true
    setHeader(config, 'Authorization', `Bearer ${newToken}`)
    return await request(config)
  } catch (refreshError) {
    // refresh token 也失效了，走完整登出。
    return forceLogout(error)
  }
}

/**
 * 处理 202：进入排队 → 轮询 → 就绪后自动重发原请求。
 *
 * 202 属于 2xx，会落在 success 分支，不要去 error 分支里等它。
 *
 * @param {import('axios').AxiosResponse} response 202 响应
 * @returns {Promise<any>} 重发后的业务响应
 */
async function handleQueued(response) {
  const body = response.data || {}
  const ticket = body.data || {}
  const token = ticket.queueToken || ticket.token
  const config = response.config || {}

  if (!token) {
    // 契约外的 202（没给票据）：无从轮询，只能如实提示。
    notifyError(body.message || '当前抢购人数过多，请稍后再试')
    return Promise.reject(new Error(body.message || 'QUEUE_TOKEN_MISSING'))
  }

  const round = Number(config.__queueRound) || 0
  if (round >= MAX_QUEUE_ROUNDS) {
    notifyError('当前抢购人数过多，请稍后再试')
    return Promise.reject(new Error('QUEUE_ROUND_EXCEEDED'))
  }

  try {
    const readyToken = await waitInQueue({
      token,
      position: ticket.position,
      estimatedWaitSeconds: ticket.estimatedWaitSeconds,
      pollInterval: ticket.pollInterval
    })
    config.__queueRound = round + 1
    setHeader(config, 'X-Queue-Token', readyToken)
    return await request(config)
  } catch (queueError) {
    if (queueError instanceof QueueError) {
      // 用户主动取消不需要报错提示。
      if (queueError.code !== 0) {
        notifyError(queueError.message)
      }
    } else {
      notifyError(resolveErrorMessage(queueError))
    }
    return Promise.reject(queueError)
  }
}

// -----------------------------------------------------------------------------
// 拦截器
// -----------------------------------------------------------------------------

request.interceptors.request.use(
  (config) => {
    const userStore = useUserStore()
    if (userStore.token) {
      setHeader(config, 'Authorization', `Bearer ${userStore.token}`)
    }
    return config
  },
  (error) => Promise.reject(error)
)

request.interceptors.response.use(
  (response) => {
    // ---- 排队：HTTP 202 是 2xx，走的是这条分支 ----
    if (response.status === HTTP_ACCEPTED) {
      return handleQueued(response)
    }

    const res = response.data

    // ---- 软失败：HTTP 200 但 body.code != 200（目前主要是 code=500）----
    // 这是改造前唯一能正常工作的路径，原样保留。
    if (res && res.code !== HTTP_OK) {
      notifyError(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }

    // ⚠️ 契约：返回整个 Result 包装体，调用方按 res.data.xxx 取值。不要扁平化。
    return res
  },
  (error) => {
    const status = error?.response?.status

    if (status === HTTP_UNAUTHORIZED) {
      return handleUnauthorized(error)
    }

    // 403 / 400 / 404 / 409 / 408 / 503 等：只提示后端中文文案，不跳转。
    // 「不能评价自己出售的商品」这类业务约束提示，跳登录页是完全错误的行为。
    notifyError(resolveErrorMessage(error))
    return Promise.reject(error)
  }
)

export default request
