<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-header">
        <span class="auth-mark">E</span>
        <h2>欢迎回来</h2>
        <p class="auth-sub">登录后即可下单、评价与追问</p>
      </div>

      <el-form ref="loginFormRef" :model="loginForm" :rules="loginRules" class="auth-form">
        <el-form-item prop="username">
          <el-input
            v-model="loginForm.username"
            placeholder="请输入用户名"
            :prefix-icon="User"
            size="large"
          />
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="loginForm.password"
            type="password"
            placeholder="请输入密码"
            :prefix-icon="Lock"
            size="large"
            show-password
            @keyup.enter="handleLogin"
          />
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            size="large"
            :loading="loading"
            class="auth-btn"
            @click="handleLogin"
          >
            登录
          </el-button>
        </el-form-item>

        <div class="auth-footer">
          <span>还没有账号？</span>
          <router-link to="/register" class="auth-link">立即注册</router-link>
        </div>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Lock, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAuth } from '@/composables/useAuth'

const router = useRouter()
const route = useRoute()
const { login } = useAuth()

const loginFormRef = ref(null)
const loading = ref(false)

const loginForm = reactive({
  username: '',
  password: ''
})

const loginRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于6位', trigger: 'blur' }
  ]
}

/**
 * 提交登录。
 *
 * 令牌落库交给 `useAuth().login`：
 * 它会把 token / refreshToken / roles / perms 分别存进独立的 key，
 * 而不是像改造前那样把整个响应体（含明文 refreshToken）塞进 userInfo。
 *
 * @returns {Promise<void>}
 */
async function handleLogin() {
  if (!loginFormRef.value) {
    return
  }

  const valid = await loginFormRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }

  loading.value = true
  try {
    await login({ username: loginForm.username, password: loginForm.password })
    ElMessage.success('登录成功')
    const redirect = route.query.redirect || '/'
    router.push(redirect)
  } catch (error) {
    // 「用户名或密码错误」等中文文案由 request.js 统一弹出。
    console.error('登录失败:', error)
  } finally {
    loading.value = false
  }
}
</script>

<style lang="scss" scoped>
.auth-page {
  min-height: calc(100vh - #{$layout-header-height} - #{$layout-footer-height});
  display: flex;
  align-items: center;
  justify-content: center;
  padding: $space-10 $space-4;
  // 改造前是 #667eea → #764ba2 的紫色渐变，与全站橙色主色完全脱节，
  // 这里统一到品牌渐变，避免「跳到登录页像换了个站」。
  background: $gradient-brand;
}

.auth-card {
  width: 400px;
  max-width: 100%;
  background: $color-bg-card;
  border-radius: $radius-xl;
  padding: $space-10;
  box-shadow: $shadow-lg;
}

.auth-header {
  text-align: center;
  margin-bottom: $space-8;

  .auth-mark {
    @include flex-center;
    width: 44px;
    height: 44px;
    margin: 0 auto $space-3;
    border-radius: $radius-md;
    background: $gradient-brand;
    color: $color-text-inverse;
    font-size: $font-2xl;
    font-weight: $font-weight-bold;
  }

  h2 {
    font-size: $font-2xl;
    font-weight: $font-weight-bold;
    color: $color-text-title;
  }

  .auth-sub {
    margin-top: $space-2;
    color: $color-text-secondary;
    font-size: $font-sm;
  }
}

.auth-form {
  .auth-btn {
    width: 100%;
    height: 44px;
    font-size: $font-md;
    letter-spacing: 2px;
  }
}

.auth-footer {
  text-align: center;
  margin-top: $space-4;
  color: $color-text-secondary;
  font-size: $font-base;

  .auth-link {
    color: $color-primary;
    margin-left: $space-1;
    font-weight: $font-weight-medium;

    &:hover {
      text-decoration: underline;
    }
  }
}
</style>
