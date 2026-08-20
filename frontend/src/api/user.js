import request from '@/utils/request'

/**
 * 用户中心接口。
 *
 * 后端 T02 已经提供了完整的 token 生命周期（refresh / logout / perms），
 * 这里把它们补齐 —— 改造前本文件只有 register / login / getUserInfo 三个方法。
 */
export default {
  /**
   * 注册。
   *
   * @param {object} data 注册表单
   * @returns {Promise<object>} Result 包装体
   */
  register(data) {
    return request.post('/user/register', data)
  },

  /**
   * 登录，返回 access + refresh 双令牌以及 user.roles / user.perms。
   *
   * @param {{username: string, password: string}} data 登录表单
   * @returns {Promise<object>} Result 包装体
   */
  login(data) {
    return request.post('/user/login', data)
  },

  /**
   * 用 refresh token 换取新的令牌对。
   *
   * ⚠️ 业务代码不要直接调用它。
   * 401 的静默刷新由 `utils/request.js` 的单飞逻辑统一发起 ——
   * 后端刷新是轮换式的，多处并发调用会互相作废凭据把用户踢下线。
   * 这里导出仅为接口完整性与手工调试。
   *
   * @param {string} token refresh token
   * @returns {Promise<object>} Result 包装体，data 为 {token, refreshToken, expiresIn}
   */
  refreshToken(token) {
    return request.post('/user/refresh', { refreshToken: token }, { skipAuthRefresh: true })
  },

  /**
   * 登出：把当前 access token 加入服务端黑名单。
   *
   * token 从 Authorization 头读取，无需传参。
   *
   * @returns {Promise<object>} Result 包装体
   */
  logout() {
    // 登出请求本身若返回 401，说明 token 早已失效，此时再去刷新毫无意义，
    // 因此显式跳过刷新流程，直接让调用方走本地清理。
    return request.post('/user/logout', null, { skipAuthRefresh: true })
  },

  /**
   * 查询用户信息。
   *
   * @param {number} userId 用户 id
   * @returns {Promise<object>} Result 包装体
   */
  getUserInfo(userId) {
    return request.get(`/user/info/${userId}`)
  },

  /**
   * 查询用户的角色与权限。
   *
   * @param {number} userId 用户 id
   * @returns {Promise<object>} Result 包装体，data 为 {roles: string[], perms: string[]}
   */
  getPerms(userId) {
    return request.get(`/user/perms/${userId}`)
  }
}
