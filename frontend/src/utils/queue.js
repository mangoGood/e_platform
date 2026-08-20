import axios from 'axios'
import { reactive } from 'vue'

/**
 * 秒杀排队客户端。
 *
 * 网关在高并发下会把 `POST /api/order/create` 拦成 HTTP 202，下发一张排队票据；
 * 客户端拿票据轮询 `GET /api/queue/status`，就绪后带 `X-Queue-Token` 头重发原请求。
 *
 * 本模块只负责「排队」这一件事：轮询 + 对外暴露一份可渲染的状态。
 * 「什么时候进入排队」「就绪后如何重发」由 utils/request.js 的响应拦截器决定，
 * 这样业务代码（orderApi.createOrder）完全感知不到排队的存在。
 *
 * 刻意使用独立的裸 axios 实例：
 *   1. 轮询本身不需要 Authorization（网关的 /api/queue/** 不走鉴权管线）；
 *   2. 走 request.js 的话，202 会再次触发排队逻辑，形成自我递归。
 */

/** 轮询专用实例，不挂任何拦截器。 */
const queueHttp = axios.create({
  baseURL: '/api',
  timeout: 10000
})

/** 轮询间隔下限（毫秒）。防止后端下发 0 导致打爆网关。 */
const MIN_POLL_INTERVAL_MS = 800

/** 轮询间隔上限（毫秒）。 */
const MAX_POLL_INTERVAL_MS = 10000

/** 最大轮询轮次。按 2s 间隔算约 5 分钟，超过即视为异常。 */
const MAX_POLL_ROUNDS = 150

/** 网关排队相关的 HTTP 状态。 */
const HTTP_OK = 200
const HTTP_ACCEPTED = 202
const HTTP_TIMEOUT = 408

/**
 * 排队遮罩的渲染状态。
 *
 * 用普通 reactive 而不是 pinia store：本模块被 request.js 在应用初始化早期引用，
 * 用 pinia 需要保证 `app.use(createPinia())` 已执行，多一个时序耦合而没有任何收益。
 */
export const queueState = reactive({
  /** 是否显示遮罩。 */
  visible: false,
  /** 前方排队人数（1 起算）。 */
  position: 0,
  /** 预计等待秒数。 */
  estimatedWaitSeconds: 0,
  /** 已等待秒数，用于给用户一个「确实在动」的反馈。 */
  elapsedSeconds: 0,
  /** waiting | ready | failed */
  status: 'waiting',
  /** 展示给用户的文案。 */
  message: ''
})

/** 用户主动取消的标记。由遮罩上的取消按钮置位。 */
let cancelled = false

/** 计时器句柄。 */
let elapsedTimer = null

/**
 * 排队过程中的可预期错误。
 *
 * 与普通网络错误区分开，便于 request.js 决定「提示什么文案」。
 */
export class QueueError extends Error {
  /**
   * @param {string} message 面向用户的中文提示
   * @param {number} code HTTP 状态码，取消时为 0
   */
  constructor(message, code = 0) {
    super(message)
    this.name = 'QueueError'
    this.code = code
    this.isQueueError = true
  }
}

/**
 * 睡眠。
 *
 * @param {number} ms 毫秒
 * @returns {Promise<void>}
 */
function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * 把后端下发的轮询间隔（秒）夹到安全区间并转成毫秒。
 *
 * @param {number|undefined} seconds 后端建议的间隔（秒）
 * @returns {number} 毫秒
 */
function normalizeInterval(seconds) {
  const ms = Number(seconds) > 0 ? Number(seconds) * 1000 : 2000
  return Math.min(Math.max(ms, MIN_POLL_INTERVAL_MS), MAX_POLL_INTERVAL_MS)
}

/**
 * 打开遮罩并开始计时。
 *
 * @param {{position: number, estimatedWaitSeconds: number}} ticket 首帧数据
 * @returns {void}
 */
function openOverlay(ticket) {
  cancelled = false
  queueState.visible = true
  queueState.status = 'waiting'
  queueState.position = Number(ticket.position) || 0
  queueState.estimatedWaitSeconds = Number(ticket.estimatedWaitSeconds) || 0
  queueState.elapsedSeconds = 0
  queueState.message = ''

  if (elapsedTimer) {
    clearInterval(elapsedTimer)
  }
  elapsedTimer = setInterval(() => {
    queueState.elapsedSeconds += 1
  }, 1000)
}

/**
 * 关闭遮罩并停止计时。
 *
 * @returns {void}
 */
function closeOverlay() {
  queueState.visible = false
  if (elapsedTimer) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
}

/**
 * 用户主动放弃排队。由 QueueOverlay 组件调用。
 *
 * 只置标记，真正的收尾在 waitInQueue 的循环里做，
 * 避免「组件关掉了遮罩但轮询还在后台跑」。
 *
 * @returns {void}
 */
export function cancelQueue() {
  cancelled = true
  queueState.status = 'failed'
  queueState.message = '已取消排队'
}

/**
 * 排队直到拿到名额。
 *
 * @param {{token: string, position?: number, estimatedWaitSeconds?: number, pollInterval?: number}} ticket
 *        202 响应体里的 data
 * @returns {Promise<string>} 就绪后的票据，调用方需把它放进 X-Queue-Token 重发原请求
 * @throws {QueueError} 超时（408）、票据失效、用户取消
 */
export async function waitInQueue(ticket) {
  let token = ticket.token
  let interval = normalizeInterval(ticket.pollInterval)

  openOverlay(ticket)

  try {
    for (let round = 0; round < MAX_POLL_ROUNDS; round += 1) {
      await sleep(interval)

      if (cancelled) {
        throw new QueueError('已取消排队', 0)
      }

      let response = null
      try {
        response = await queueHttp.get('/queue/status', { params: { token } })
      } catch (error) {
        const status = error?.response?.status
        const backendMessage = error?.response?.data?.message
        if (status === HTTP_TIMEOUT) {
          throw new QueueError(backendMessage || '排队超时，请重新提交', HTTP_TIMEOUT)
        }
        // 单次网络抖动不该让用户前功尽弃：没有响应体就重试下一轮。
        if (!error?.response) {
          continue
        }
        throw new QueueError(backendMessage || '排队失败，请重新提交', status || 0)
      }

      const body = response.data || {}
      const data = body.data || {}

      if (response.status === HTTP_OK && data.ready) {
        queueState.status = 'ready'
        queueState.position = 0
        return data.token || data.queueToken || token
      }

      if (response.status === HTTP_ACCEPTED) {
        // 票据可能被服务端轮换，始终以最新一次返回的为准。
        token = data.token || data.queueToken || token
        queueState.position = Number(data.position) || 0
        queueState.estimatedWaitSeconds = Number(data.estimatedWaitSeconds) || 0
        interval = normalizeInterval(data.pollInterval)
        continue
      }

      // 既不是 200-ready 也不是 202：契约外的响应，按失败处理而不是空转。
      throw new QueueError(body.message || '排队状态异常，请重新提交', response.status)
    }

    throw new QueueError('排队超时，请重新提交', HTTP_TIMEOUT)
  } finally {
    closeOverlay()
  }
}
