<template>
  <div id="app">
    <router-view />
    <!-- 排队遮罩挂在根节点：下单入口分布在 ProductDetail / Cart 多处，
         挂一次就能全局复用，页面本身不需要感知排队的存在。 -->
    <QueueOverlay />
  </div>
</template>

<script setup>
import QueueOverlay from '@/components/QueueOverlay.vue'
</script>

<style lang="scss">
#app {
  font-family: $font-family-base;
  color: $color-text-primary;
}
</style>
