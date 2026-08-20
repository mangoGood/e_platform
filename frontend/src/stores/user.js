import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

/**
 * 登录态存储。
 *
 * <h3>为什么 refreshToken / roles / perms 要独立成 key</h3>
 * 改造前 `Login.vue` 把整个登录响应 `res.data` 一股脑塞进 `userInfo`，
 * 于是 refresh token 明文混在用户资料里，roles 藏在 `userInfo.user.roles` 里，
 * 全站没有一行代码去读 —— 数据在，但等于不存在。
 *
 * 这里把三者拆成独立 key 管理，并保留一次性迁移逻辑，
 * 让改造前已经登录的用户刷新页面后不掉线、且能立刻拿到角色。
 */

const STORAGE_KEYS = {
  token: 'token',
  refreshToken: 'refreshToken',
  userInfo: 'userInfo',
  roles: 'roles',
  perms: 'perms',
  expiresAt: 'tokenExpiresAt'
}

/** 角色码常量。与后端 `sys_role.code` 一致。 */
export const ROLE_BUYER = 'ROLE_BUYER'
export const ROLE_SELLER = 'ROLE_SELLER'
export const ROLE_ADMIN = 'ROLE_ADMIN'

/** 存量登录态里卖家的 userType 魔法数字，仅作兼容兜底使用。 */
const LEGACY_USER_TYPE_SELLER = 2

/**
 * 安全地从 localStorage 读取 JSON。
 *
 * localStorage 里可能残留手工改坏的值，JSON.parse 抛异常会让整个 store 初始化失败，
 * 进而白屏 —— 这里一律降级为默认值。
 *
 * @param {string} key 存储键
 * @param {any} fallback 解析失败时的默认值
 * @returns {any} 解析结果
 */
function readJson(key, fallback = null) {
  try {
    const raw = localStorage.getItem(key)
    if (raw === null || raw === '' || raw === 'undefined') {
      return fallback
    }
    const parsed = JSON.parse(raw)
    return parsed === null ? fallback : parsed
  } catch (error) {
    console.warn(`[userStore] 读取 ${key} 失败，已重置：`, error)
    localStorage.removeItem(key)
    return fallback
  }
}

/**
 * 写入 JSON，值为空时移除该键。
 *
 * @param {string} key 存储键
 * @param {any} value 值
 * @returns {void}
 */
function writeJson(key, value) {
  if (value === null || value === undefined) {
    localStorage.removeItem(key)
    return
  }
  localStorage.setItem(key, JSON.stringify(value))
}

/**
 * 保证结果是字符串数组。后端字段缺失或类型异常时不至于让 `.includes` 崩掉。
 *
 * @param {any} value 原始值
 * @returns {string[]} 字符串数组
 */
function toStringArray(value) {
  if (!Array.isArray(value)) {
    return []
  }
  return value.filter((item) => typeof item === 'string')
}

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem(STORAGE_KEYS.token) || '')
  const refreshToken = ref(localStorage.getItem(STORAGE_KEYS.refreshToken) || '')
  const userInfo = ref(readJson(STORAGE_KEYS.userInfo, null))
  const roles = ref(toStringArray(readJson(STORAGE_KEYS.roles, [])))
  const perms = ref(toStringArray(readJson(STORAGE_KEYS.perms, [])))
  const expiresAt = ref(Number(localStorage.getItem(STORAGE_KEYS.expiresAt)) || 0)

  /**
   * 迁移改造前的存量登录态。
   *
   * 老版本把 refreshToken / user.roles / user.perms 全塞在 userInfo 里，
   * 这里在 store 初始化时提取一次，之后按新结构维护。
   *
   * @returns {void}
   */
  function migrateLegacyState() {
    const legacy = userInfo.value
    if (!legacy || typeof legacy !== 'object') {
      return
    }

    if (!refreshToken.value && typeof legacy.refreshToken === 'string' && legacy.refreshToken) {
      refreshToken.value = legacy.refreshToken
      localStorage.setItem(STORAGE_KEYS.refreshToken, legacy.refreshToken)
    }

    const nested = legacy.user && typeof legacy.user === 'object' ? legacy.user : null
    if (roles.value.length === 0) {
      const legacyRoles = toStringArray(nested?.roles || legacy.roles)
      if (legacyRoles.length > 0) {
        roles.value = legacyRoles
        writeJson(STORAGE_KEYS.roles, legacyRoles)
      }
    }
    if (perms.value.length === 0) {
      const legacyPerms = toStringArray(nested?.perms || legacy.perms)
      if (legacyPerms.length > 0) {
        perms.value = legacyPerms
        writeJson(STORAGE_KEYS.perms, legacyPerms)
      }
    }

    // 顺手把明文 refreshToken 从 userInfo 里抹掉，避免继续混着存。
    if (legacy.refreshToken) {
      const sanitized = { ...legacy }
      delete sanitized.refreshToken
      userInfo.value = sanitized
      writeJson(STORAGE_KEYS.userInfo, sanitized)
    }
  }

  migrateLegacyState()

  const isLoggedIn = computed(() => !!token.value)
  const userId = computed(() => userInfo.value?.userId || userInfo.value?.id || 0)
  const userType = computed(() => userInfo.value?.userType || 0)
  const username = computed(() => userInfo.value?.username || '')
  const avatar = computed(() => userInfo.value?.avatar || '')

  /**
   * 是否拥有指定角色。
   *
   * @param {string} code 角色码，如 ROLE_SELLER
   * @returns {boolean} 是否拥有
   */
  function hasRole(code) {
    return roles.value.includes(code)
  }

  /**
   * 是否拥有指定权限码。
   *
   * @param {string} code 权限码，如 comment:reply
   * @returns {boolean} 是否拥有
   */
  function hasPerm(code) {
    return perms.value.includes(code)
  }

  /**
   * 是否为卖家。
   *
   * 以 RBAC 角色为准；roles 为空时（存量登录态尚未重新登录）退回 userType 兜底，
   * 避免把已经登录的老用户挡在卖家中心外面。
   */
  const isSeller = computed(() => {
    if (roles.value.length > 0) {
      return hasRole(ROLE_SELLER)
    }
    return userType.value === LEGACY_USER_TYPE_SELLER
  })

  /** 是否为管理员。存量登录态没有这个概念，只能靠角色判断。 */
  const isAdmin = computed(() => hasRole(ROLE_ADMIN))

  /**
   * 设置 access token。
   *
   * @param {string} newToken 新 token
   * @returns {void}
   */
  function setToken(newToken) {
    token.value = newToken || ''
    if (token.value) {
      localStorage.setItem(STORAGE_KEYS.token, token.value)
    } else {
      localStorage.removeItem(STORAGE_KEYS.token)
    }
  }

  /**
   * 设置 refresh token。
   *
   * @param {string} newRefreshToken 新 refresh token
   * @returns {void}
   */
  function setRefreshToken(newRefreshToken) {
    refreshToken.value = newRefreshToken || ''
    if (refreshToken.value) {
      localStorage.setItem(STORAGE_KEYS.refreshToken, refreshToken.value)
    } else {
      localStorage.removeItem(STORAGE_KEYS.refreshToken)
    }
  }

  /**
   * 保存用户资料。会剔除混在里面的 refreshToken 与嵌套的 user 字段。
   *
   * @param {object} info 登录响应中的 data
   * @returns {void}
   */
  function setUserInfo(info) {
    if (!info) {
      userInfo.value = null
      localStorage.removeItem(STORAGE_KEYS.userInfo)
      return
    }
    const nested = info.user && typeof info.user === 'object' ? info.user : {}
    const profile = {
      userId: info.userId || nested.id || 0,
      username: info.username || nested.username || '',
      userType: info.userType ?? nested.userType ?? 0,
      avatar: info.avatar || nested.avatar || '',
      email: info.email || nested.email || '',
      phone: info.phone || nested.phone || ''
    }
    userInfo.value = profile
    writeJson(STORAGE_KEYS.userInfo, profile)
  }

  /**
   * 保存角色与权限。
   *
   * @param {string[]} nextRoles 角色码列表
   * @param {string[]} nextPerms 权限码列表
   * @returns {void}
   */
  function setAuthorities(nextRoles, nextPerms) {
    roles.value = toStringArray(nextRoles)
    perms.value = toStringArray(nextPerms)
    writeJson(STORAGE_KEYS.roles, roles.value)
    writeJson(STORAGE_KEYS.perms, perms.value)
  }

  /**
   * 应用一对新令牌（登录成功 / 刷新成功都走这里）。
   *
   * @param {{token: string, refreshToken?: string, expiresIn?: number}} pair 令牌对
   * @returns {void}
   */
  function applyTokenPair(pair) {
    if (!pair) {
      return
    }
    setToken(pair.token)
    // 后端刷新是轮换式的：每次都会下发新的 refresh token，必须覆盖存储，
    // 否则下一次刷新会拿着已作废的旧凭据去换，直接掉线。
    if (pair.refreshToken) {
      setRefreshToken(pair.refreshToken)
    }
    if (pair.expiresIn) {
      expiresAt.value = Date.now() + Number(pair.expiresIn) * 1000
      localStorage.setItem(STORAGE_KEYS.expiresAt, String(expiresAt.value))
    }
  }

  /**
   * 一次性写入完整登录态。
   *
   * @param {object} loginData 登录接口返回的 data
   * @returns {void}
   */
  function setLoginState(loginData) {
    if (!loginData) {
      return
    }
    applyTokenPair(loginData)
    setUserInfo(loginData)
    const nested = loginData.user && typeof loginData.user === 'object' ? loginData.user : {}
    setAuthorities(nested.roles || loginData.roles, nested.perms || loginData.perms)
  }

  /**
   * 清空本地登录态。不调用后端接口。
   *
   * @returns {void}
   */
  function clearAuth() {
    token.value = ''
    refreshToken.value = ''
    userInfo.value = null
    roles.value = []
    perms.value = []
    expiresAt.value = 0
    Object.values(STORAGE_KEYS).forEach((key) => localStorage.removeItem(key))
  }

  return {
    token,
    refreshToken,
    userInfo,
    roles,
    perms,
    expiresAt,
    isLoggedIn,
    userId,
    userType,
    username,
    avatar,
    isSeller,
    isAdmin,
    hasRole,
    hasPerm,
    setToken,
    setRefreshToken,
    setUserInfo,
    setAuthorities,
    applyTokenPair,
    setLoginState,
    clearAuth,
    // 保留 logout 别名：改造前多处直接调用 userStore.logout()，
    // 删掉它没有任何收益，只会制造无谓的破坏面。
    logout: clearAuth
  }
})
