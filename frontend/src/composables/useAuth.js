import userApi from '@/api/user'
import { useUserStore } from '@/stores/user'
import { useCartStore } from '@/stores/cart'

/**
 * 登录 / 登出流程。
 *
 * 放在 composable 而不是 store 里，是为了避开循环依赖：
 * `stores/user` ← `utils/request` ← `api/user`，
 * 如果让 store 直接 import api，就会形成 store → api → request → store 的环。
 * composable 位于这条链之外，谁都不依赖它，因此可以安全地同时使用两侧。
 */
export function useAuth() {
  const userStore = useUserStore()
  const cartStore = useCartStore()

  /**
   * 登录并写入完整登录态（token / refreshToken / userInfo / roles / perms）。
   *
   * @param {{username: string, password: string}} credentials 登录表单
   * @returns {Promise<object>} 登录响应的 data
   */
  async function login(credentials) {
    const res = await userApi.login(credentials)
    const data = res.data || {}
    userStore.setLoginState(data)
    // 登录后立刻同步一次购物车角标，避免用户看到「登录了但角标是空的」。
    await cartStore.refresh()
    return data
  }

  /**
   * 登出。
   *
   * 先通知后端把当前 token 加入黑名单，再清理本地。
   * <b>后端失败不能卡住用户登出</b> —— 无论接口成败都会执行本地清理，
   * 否则网络一抖用户就退不出去了。
   *
   * 改造前这里是纯前端清 storage，等于登出后旧 token 在 2 小时内仍然有效。
   *
   * @returns {Promise<void>}
   */
  async function logout() {
    try {
      if (userStore.token) {
        await userApi.logout()
      }
    } catch (error) {
      console.warn('服务端登出失败，仍继续清理本地登录态:', error)
    } finally {
      userStore.clearAuth()
      cartStore.clear()
    }
  }

  return { login, logout }
}
