<template>
  <div class="main-layout">
    <header class="header">
      <div class="container header-content">
        <div class="logo" @click="$router.push('/')">
          <h1>电商平台</h1>
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
              @keyup.enter="handleSearch"
            >
              <template #append>
                <el-button icon="Search" @click="handleSearch" />
              </template>
            </el-input>
          </div>
          
          <div class="cart-icon" @click="$router.push('/cart')">
            <el-badge :value="cartCount" :hidden="cartCount === 0">
              <el-icon size="24"><ShoppingCart /></el-icon>
            </el-badge>
          </div>
          
          <div v-if="userStore.isLoggedIn" class="user-info">
            <el-dropdown @command="handleCommand">
              <span class="user-dropdown">
                <el-avatar :size="32" :src="userStore.userInfo?.avatar">
                  {{ userStore.username?.charAt(0).toUpperCase() }}
                </el-avatar>
                <span class="username">{{ userStore.username }}</span>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="orders">我的订单</el-dropdown-item>
                  <el-dropdown-item v-if="userStore.userType === 2" command="seller">
                    卖家中心
                  </el-dropdown-item>
                  <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
          <div v-else class="auth-buttons">
            <el-button text @click="$router.push('/login')">登录</el-button>
            <el-button type="primary" @click="$router.push('/register')">注册</el-button>
          </div>
        </div>
      </div>
    </header>
    
    <main class="main-content">
      <router-view />
    </main>
    
    <footer class="footer">
      <div class="container">
        <div class="footer-content">
          <div class="footer-section">
            <h3>关于我们</h3>
            <p>电商平台致力于为用户提供优质的购物体验</p>
          </div>
          <div class="footer-section">
            <h3>联系方式</h3>
            <p>客服电话：400-123-4567</p>
            <p>邮箱：support@eplatform.com</p>
          </div>
          <div class="footer-section">
            <h3>关注我们</h3>
            <p>微信公众号：电商平台</p>
          </div>
        </div>
        <div class="footer-bottom">
          <p>© 2024 电商平台 版权所有</p>
        </div>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'

const router = useRouter()
const userStore = useUserStore()
const searchKeyword = ref('')

const cartCount = computed(() => {
  return 0
})

const handleSearch = () => {
  if (searchKeyword.value.trim()) {
    router.push({
      name: 'Products',
      query: { keyword: searchKeyword.value }
    })
  }
}

const handleCommand = (command) => {
  switch (command) {
    case 'orders':
      router.push('/orders')
      break
    case 'seller':
      router.push('/seller')
      break
    case 'logout':
      userStore.logout()
      ElMessage.success('退出登录成功')
      router.push('/')
      break
  }
}
</script>

<style lang="scss" scoped>
.main-layout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.header {
  background-color: #fff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  position: sticky;
  top: 0;
  z-index: 100;
  
  .header-content {
    display: flex;
    align-items: center;
    height: 60px;
    gap: 30px;
  }
  
  .logo {
    cursor: pointer;
    
    h1 {
      font-size: 24px;
      color: #ff6700;
      font-weight: bold;
    }
  }
  
  .nav-menu {
    display: flex;
    gap: 20px;
    
    .nav-item {
      color: #333;
      text-decoration: none;
      padding: 8px 16px;
      border-radius: 4px;
      transition: all 0.3s;
      
      &:hover {
        background-color: #f5f5f5;
        color: #ff6700;
      }
      
      &.router-link-active {
        color: #ff6700;
        font-weight: bold;
      }
    }
  }
  
  .header-right {
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: 20px;
    
    .search-box {
      width: 300px;
    }
    
    .cart-icon {
      cursor: pointer;
      display: flex;
      align-items: center;
      
      &:hover {
        color: #ff6700;
      }
    }
    
    .user-info {
      .user-dropdown {
        display: flex;
        align-items: center;
        gap: 8px;
        cursor: pointer;
        
        .username {
          color: #333;
        }
      }
    }
    
    .auth-buttons {
      display: flex;
      gap: 10px;
    }
  }
}

.main-content {
  flex: 1;
}

.footer {
  background-color: #333;
  color: #fff;
  padding: 40px 0 20px;
  margin-top: auto;
  
  .footer-content {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 40px;
    margin-bottom: 30px;
  }
  
  .footer-section {
    h3 {
      font-size: 18px;
      margin-bottom: 15px;
      color: #ff6700;
    }
    
    p {
      color: #999;
      line-height: 1.8;
    }
  }
  
  .footer-bottom {
    text-align: center;
    padding-top: 20px;
    border-top: 1px solid #444;
    color: #999;
  }
}
</style>
