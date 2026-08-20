import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import orderApi from '@/api/order'
import { useUserStore } from '@/stores/user'

/**
 * 购物车状态。
 *
 * 改造前 `MainLayout.vue` 的角标是 `computed(() => 0)` —— 一个永远不亮的死角标。
 * 这里把它接上真实数据：Header 只读 `count`，谁改了购物车就调 `refresh()`。
 */
export const useCartStore = defineStore('cart', () => {
  /** 购物车条目原始数据。 */
  const items = ref([])

  /** 是否正在加载，避免并发重复拉取。 */
  const loading = ref(false)

  /**
   * 角标数字。
   *
   * 取「商品种类数」而不是「件数总和」：主流电商（含淘宝/京东）的角标语义都是种类数，
   * 且加购同一商品 10 件时角标跳到 10 会让用户误以为加错了。
   */
  const count = computed(() => items.value.length)

  /** 件数总和，结算页等需要精确件数的地方用。 */
  const totalQuantity = computed(() =>
    items.value.reduce((sum, item) => sum + (Number(item.quantity) || 0), 0)
  )

  /**
   * 拉取购物车。未登录时直接清空，不发请求。
   *
   * @returns {Promise<void>}
   */
  async function refresh() {
    const userStore = useUserStore()
    if (!userStore.isLoggedIn) {
      items.value = []
      return
    }
    if (loading.value) {
      return
    }
    loading.value = true
    try {
      const res = await orderApi.getCart()
      items.value = Array.isArray(res.data) ? res.data : []
    } catch (error) {
      // 角标是辅助信息，拉取失败不应该打断用户当前操作，
      // 错误提示已由拦截器统一处理，这里只保留现场。
      console.error('加载购物车失败:', error)
    } finally {
      loading.value = false
    }
  }

  /**
   * 直接设置条目（Cart 页面本地已有完整数据时避免重复请求）。
   *
   * @param {Array<object>} nextItems 条目列表
   * @returns {void}
   */
  function setItems(nextItems) {
    items.value = Array.isArray(nextItems) ? nextItems : []
  }

  /**
   * 清空本地购物车状态。
   *
   * @returns {void}
   */
  function clear() {
    items.value = []
  }

  return {
    items,
    loading,
    count,
    totalQuantity,
    refresh,
    setItems,
    clear
  }
})
