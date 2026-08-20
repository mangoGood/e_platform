<template>
  <div class="main-layout">
    <AppHeader />

    <main class="main-layout__body">
      <router-view v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>

    <AppFooter />
  </div>
</template>

<script setup>
/**
 * 主布局：仅负责 Header / 内容区 / Footer 三段式骨架。
 * 具体的导航、搜索、购物车角标、用户下拉逻辑已拆分到
 * layouts/components/AppHeader.vue，页脚拆分到 AppFooter.vue。
 */
import AppHeader from './components/AppHeader.vue'
import AppFooter from './components/AppFooter.vue'
</script>

<style lang="scss" scoped>
.main-layout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background-color: $color-bg-page;

  &__body {
    flex: 1;
    min-width: 0;
  }
}
</style>
