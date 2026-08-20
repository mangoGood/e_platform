import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const routes = [
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    children: [
      {
        path: '',
        name: 'Home',
        component: () => import('@/views/Home.vue'),
        meta: { title: '首页' }
      },
      {
        path: 'products',
        name: 'Products',
        component: () => import('@/views/Products.vue'),
        meta: { title: '商品列表' }
      },
      {
        path: 'product/:id',
        name: 'ProductDetail',
        component: () => import('@/views/ProductDetail.vue'),
        meta: { title: '商品详情' }
      },
      {
        path: 'cart',
        name: 'Cart',
        component: () => import('@/views/Cart.vue'),
        meta: { title: '购物车', requiresAuth: true }
      },
      {
        path: 'orders',
        name: 'Orders',
        component: () => import('@/views/Orders.vue'),
        meta: { title: '我的订单', requiresAuth: true }
      },
      {
        path: 'seller',
        name: 'SellerCenter',
        component: () => import('@/views/seller/SellerCenter.vue'),
        meta: { title: '卖家中心', requiresAuth: true, requiresSeller: true }
      },
      {
        path: 'seller/products',
        name: 'SellerProducts',
        component: () => import('@/views/seller/SellerProducts.vue'),
        meta: { title: '商品管理', requiresAuth: true, requiresSeller: true }
      },
      {
        path: 'seller/orders',
        name: 'SellerOrders',
        component: () => import('@/views/seller/SellerOrders.vue'),
        meta: { title: '订单管理', requiresAuth: true, requiresSeller: true }
      }
    ]
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/Register.vue'),
    meta: { title: '注册' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 全局前置守卫。
 *
 * 卖家路由的判定改为基于 RBAC 角色（`ROLE_SELLER`）而不是
 * 改造前硬编码的 `userStore.userType !== 2` 魔法数字。
 * 兼容兜底在 `userStore.isSeller` 内部：roles 为空（存量登录态）时才回退看 userType，
 * 这样已经登录的老用户不会在改造后被挡在卖家中心外面。
 */
router.beforeEach((to, from, next) => {
  document.title = to.meta.title || '电商平台'

  const userStore = useUserStore()

  if (to.meta.requiresAuth && !userStore.isLoggedIn) {
    next({ name: 'Login', query: { redirect: to.fullPath } })
    return
  }

  if (to.meta.requiresSeller && !userStore.isSeller) {
    next({ name: 'Home' })
    return
  }

  if (to.meta.requiresAdmin && !userStore.isAdmin) {
    next({ name: 'Home' })
    return
  }

  next()
})

export default router
