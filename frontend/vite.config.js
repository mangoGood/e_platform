import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

/**
 * 设计 Token 文件的绝对 posix 路径。
 *
 * 这里刻意用绝对路径而不是 `@/styles/variables.scss`：
 * sass 的 `@use` 由 dart-sass 自己解析，不走 vite 的 resolve.alias，
 * 用别名在部分 sass 版本上会解析失败。绝对路径是最不容易出问题的写法。
 */
const designTokens = path
  .resolve(__dirname, 'src/styles/variables.scss')
  .replace(/\\/g, '/')

/**
 * 后端网关地址。所有 /api 请求统一走它。
 * 通过环境变量覆盖，便于连非本机的联调环境。
 */
const gatewayTarget = process.env.VITE_GATEWAY_URL || 'http://localhost:8088'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src')
    }
  },
  css: {
    preprocessorOptions: {
      scss: {
        /**
         * 把设计 Token 自动注入每一个 scss / <style lang="scss"> 的顶部，
         * 组件里就不必逐个手写 @use 了。
         *
         * 必须跳过 variables.scss 自身 —— 否则它会 @use 自己，
         * dart-sass 会直接报 "This module was already loaded" 而中断构建。
         *
         * @param {string} source 原始 scss 源码
         * @param {string} filename 当前文件绝对路径
         * @returns {string} 注入后的源码
         */
        additionalData(source, filename) {
          if (filename.replace(/\\/g, '/') === designTokens) {
            return source
          }
          return `@use "${designTokens}" as *;\n${source}`
        }
      }
    }
  },
  server: {
    port: 3000,
    proxy: {
      // 前端 dev server 与网关不同源，没有代理时所有 /api 请求都会 404。
      '/api': {
        target: gatewayTarget,
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets'
  }
})
