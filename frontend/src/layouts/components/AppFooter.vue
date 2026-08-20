<template>
  <footer class="app-footer">
    <div class="container">
      <div class="footer-content">
        <div class="footer-section brand">
          <div class="brand-line">
            <span class="logo-mark">E</span>
            <span class="brand-name">电商平台</span>
          </div>
          <p>致力于为用户提供优质、可靠、有温度的购物体验。</p>
        </div>
        <div class="footer-section">
          <h3>联系方式</h3>
          <p>客服电话：400-123-4567</p>
          <p>邮箱：support@eplatform.com</p>
        </div>
        <div class="footer-section">
          <h3>关注我们</h3>
          <p>微信公众号：电商平台</p>
          <p>服务时间：9:00 - 21:00</p>
        </div>
      </div>
      <div class="footer-bottom">
        <p>© 2024 电商平台 版权所有</p>
      </div>
    </div>
  </footer>
</template>

<script setup>
/**
 * 站点底部。纯静态展示，从 MainLayout 拆出以保持布局文件精简。
 */
</script>

<style lang="scss" scoped>
.app-footer {
  background-color: $color-bg-inverse;
  color: $gray-400;
  padding: $space-10 0 $space-5;
  margin-top: auto;
}

.footer-content {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr;
  gap: $space-10;
  margin-bottom: $space-8;
}

.footer-section {
  h3 {
    font-size: $font-md;
    margin-bottom: $space-3;
    color: $color-text-inverse;
  }

  p {
    color: $gray-500;
    line-height: $line-height-loose;
    font-size: $font-base;
  }

  &.brand {
    .brand-line {
      display: flex;
      align-items: center;
      gap: $space-2;
      margin-bottom: $space-3;
    }

    .logo-mark {
      @include flex-center;
      width: 26px;
      height: 26px;
      border-radius: $radius-sm;
      background: $gradient-brand;
      color: $color-text-inverse;
      font-size: $font-md;
      font-weight: $font-weight-bold;
    }

    .brand-name {
      font-size: $font-lg;
      font-weight: $font-weight-bold;
      color: $color-text-inverse;
    }
  }
}

.footer-bottom {
  text-align: center;
  padding-top: $space-5;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  color: $gray-600;
  font-size: $font-sm;
}

@media (max-width: 768px) {
  .footer-content {
    grid-template-columns: 1fr;
    gap: $space-6;
  }
}
</style>
