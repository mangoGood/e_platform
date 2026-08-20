<template>
  <header class="app-header">
    <div class="container header-content">
      <div class="logo" @click="router.push('/')">
        <span class="logo-mark">E</span>
        <span class="logo-text">电商平台</span>
      </div>

      <nav class="nav-menu">
        <router-link to="/" class="nav-item">首页</router-link>
        <router-link to="/products" class="nav-item">全部商品</router-link>
      </nav>

      <div class="header-right">
        <div class="search-box">
          <el-input
            v-model="searchKeyword"
            placeholder="搜索商品"
            clearable
            @keyup.enter="handleSearch"
          >
            <template #append>
              <el-button :icon="Search" @click="handleSearch" />
            </template>
          </el-input>
        </div>

        <div class="cart-entry" title="购物车" @click="router.push('/cart')">
          <el-badge :value="cartStore.count" :hidden="cartStore.count === 0" :max="99">
            <el-icon :size="22"><ShoppingCart /></el-icon>
          </el-badge>
        </div>

        <div v-if="userStore.isLoggedIn" class="user-info">
          <el-dropdown @command="handleCommand">
            <span class="user-dropdown">
              <el-avatar :size="32" :src="userStore.avatar">
                {{ userStore.username?.charAt(0).toUpperCase() }}
              </el-avatar>
              <span class="username">{{ userStore.username }}</span>
              <el-icon class="caret"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="orders">我的订单</el-dropdown-item>
                <el-dropdown-item v-if="userStore.isSeller" command="seller">
                  卖家中心
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <div v-else class="auth-buttons">
          <el-button text @click="router.push('/login')">登录</el-button>
          <!-- 主题已统一覆盖 --el-color-primary，这里不再需要任何 scoped 补丁 -->
          <el-button type="primary" @click="router.push('/register')">注册</el-button>
        </div>
      </div>
    </div>
  </header>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ArrowDown, Search, ShoppingCart } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { useCartStore } from '@/stores/cart'
import { useAuth } from '@/composables/useAuth'

/**
 * 站点头部：Logo / 导航 / 搜索 / 购物车角标 / 用户菜单。
 *
 * 从 260 行的 MainLayout 里拆出来，MainLayout 只保留骨架。
 */

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const cartStore = useCartStore()
const { logout } = useAuth()

const searchKeyword = ref(route.query.keyword || '')

onMounted(() => {
  // 首屏同步一次角标。未登录时 store 内部会直接短路，不会产生无谓的 401。
  cartStore.refresh()
})

// 登录状态变化（登录成功 / 被 401 踢下线）时同步角标
watch(
  () => userStore.isLoggedIn,
  (loggedIn) => {
    if (loggedIn) {
      cartStore.refresh()
    } else {
      cartStore.clear()
    }
  }
)

// 从商品列表页跳走再回来时保持搜索框与 URL 一致
watch(
  () => route.query.keyword,
  (keyword) => {
    searchKeyword.value = keyword || ''
  }
)

/**
 * 执行搜索。
 *
 * @returns {void}
 */
const handleSearch = () => {
  const keyword = searchKeyword.value.trim()
  if (!keyword) {
    return
  }
  router.push({ name: 'Products', query: { keyword } })
}

/**
 * 用户菜单命令分发。
 *
 * @param {string} command 命令名
 * @returns {Promise<void>}
 */
const handleCommand = async (command) => {
  if (command === 'orders') {
    router.push('/orders')
    return
  }
  if (command === 'seller') {
    router.push('/seller')
    return
  }
  if (command === 'logout') {
    // 先通知后端把 token 拉黑，再清本地；后端失败也不阻塞（见 useAuth）。
    await logout()
    ElMessage.success('退出登录成功')
    router.push('/')
  }
}
</script>

<style lang="scss" scoped>
.app-header {
  position: sticky;
  top: 0;
  z-index: $z-header;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid $color-border-lighter;
  box-shadow: $shadow-xs;
}

.header-content {
  display: flex;
  align-items: center;
  height: $layout-header-height;
  gap: $space-8;
}

.logo {
  display: flex;
  align-items: center;
  gap: $space-2;
  cursor: pointer;
  flex-shrink: 0;

  .logo-mark {
    @include flex-center;
    width: 30px;
    height: 30px;
    border-radius: $radius-sm;
    background: $gradient-brand;
    color: $color-text-inverse;
    font-size: $font-lg;
    font-weight: $font-weight-bold;
  }

  .logo-text {
    font-size: $font-xl;
    font-weight: $font-weight-bold;
    color: $color-text-title;
    letter-spacing: 0.5px;
  }
}

.nav-menu {
  display: flex;
  gap: $space-2;

  .nav-item {
    position: relative;
    color: $color-text-regular;
    padding: $space-2 $space-3;
    border-radius: $radius-sm;
    font-weight: $font-weight-medium;
    transition: color $transition-fast, background-color $transition-fast;
    white-space: nowrap;

    &:hover {
      background-color: $brand-50;
      color: $color-primary;
    }

    &.router-link-exact-active {
      color: $color-primary;

      &::after {
        content: '';
        position: absolute;
        left: 50%;
        bottom: -2px;
        transform: translateX(-50%);
        width: 18px;
        height: 2px;
        border-radius: $radius-pill;
        background: $color-primary;
      }
    }
  }
}

.header-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: $space-5;

  .search-box {
    width: 260px;
  }

  .cart-entry {
    cursor: pointer;
    display: flex;
    align-items: center;
    color: $color-text-regular;
    transition: color $transition-fast;

    &:hover {
      color: $color-primary;
    }
  }

  .user-info {
    .user-dropdown {
      display: flex;
      align-items: center;
      gap: $space-2;
      cursor: pointer;
      outline: none;

      .username {
        color: $color-text-primary;
        font-weight: $font-weight-medium;
        max-width: 100px;
        @include text-ellipsis;
      }

      .caret {
        color: $color-text-secondary;
        font-size: $font-xs;
      }
    }
  }

  .auth-buttons {
    display: flex;
    gap: $space-2;
  }
}

@media (max-width: 992px) {
  .header-content {
    gap: $space-4;
  }

  .header-right .search-box {
    width: 180px;
  }

  .nav-menu {
    display: none;
  }
}
</style>
