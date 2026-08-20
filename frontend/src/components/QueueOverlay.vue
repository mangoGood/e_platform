<template>
  <transition name="queue-fade">
    <div v-if="queueState.visible" class="queue-overlay">
      <div class="queue-card">
        <div class="queue-spinner">
          <span class="dot" />
          <span class="dot" />
          <span class="dot" />
        </div>

        <h3 class="queue-title">正在排队中</h3>
        <p class="queue-subtitle">当前抢购人数较多，已为你占好位置，请勿关闭页面</p>

        <div class="queue-stats">
          <div class="stat">
            <span class="stat-value">{{ aheadCount }}</span>
            <span class="stat-label">前方还有（人）</span>
          </div>
          <div class="stat-divider" />
          <div class="stat">
            <span class="stat-value">{{ remainingSeconds }}</span>
            <span class="stat-label">预计等待（秒）</span>
          </div>
        </div>

        <el-progress
          :percentage="progress"
          :show-text="false"
          :stroke-width="6"
          class="queue-progress"
        />

        <p class="queue-elapsed">已等待 {{ queueState.elapsedSeconds }} 秒，就绪后将自动为你提交订单</p>

        <el-button text class="queue-cancel" @click="handleCancel">放弃排队</el-button>
      </div>
    </div>
  </transition>
</template>

<script setup>
import { computed } from 'vue'
import { queueState, cancelQueue } from '@/utils/queue'

/**
 * 秒杀排队遮罩。
 *
 * 只做展示与「放弃」。轮询、重发原请求都在 utils/queue.js + utils/request.js 里完成，
 * 因此本组件挂载一次即可全局生效，页面无需感知排队的存在。
 */

/** 前方等待人数。position 是 1 起算的自身位次，减 1 才是「前方还有几人」。 */
const aheadCount = computed(() => Math.max(0, (Number(queueState.position) || 0) - 1))

/** 剩余预计秒数，随已等待时间递减，避免数字长时间不动让用户以为卡死。 */
const remainingSeconds = computed(() => {
  const estimated = Number(queueState.estimatedWaitSeconds) || 0
  return Math.max(0, estimated - queueState.elapsedSeconds)
})

/** 进度条百分比。没有预估值时给一个缓慢增长的假进度，纯粹为了视觉上「在动」。 */
const progress = computed(() => {
  const estimated = Number(queueState.estimatedWaitSeconds) || 0
  if (estimated <= 0) {
    return Math.min(95, queueState.elapsedSeconds * 5)
  }
  return Math.min(99, Math.round((queueState.elapsedSeconds / estimated) * 100))
})

/**
 * 放弃排队。
 *
 * @returns {void}
 */
const handleCancel = () => {
  cancelQueue()
}
</script>

<style lang="scss" scoped>
.queue-overlay {
  position: fixed;
  inset: 0;
  z-index: $z-overlay;
  @include flex-center;
  background: rgba(29, 33, 41, 0.55);
  backdrop-filter: blur(4px);
}

.queue-card {
  width: 380px;
  max-width: calc(100vw - #{$space-10});
  padding: $space-8 $space-6 $space-6;
  border-radius: $radius-xl;
  background: $color-bg-card;
  box-shadow: $shadow-lg;
  text-align: center;
}

.queue-spinner {
  display: flex;
  justify-content: center;
  gap: $space-2;
  margin-bottom: $space-5;

  .dot {
    width: 10px;
    height: 10px;
    border-radius: $radius-pill;
    background: $color-primary;
    animation: queue-bounce 1.2s infinite ease-in-out;

    &:nth-child(2) {
      animation-delay: 0.15s;
    }

    &:nth-child(3) {
      animation-delay: 0.3s;
    }
  }
}

@keyframes queue-bounce {
  0%,
  70%,
  100% {
    transform: translateY(0);
    opacity: 0.45;
  }

  35% {
    transform: translateY(-8px);
    opacity: 1;
  }
}

.queue-title {
  font-size: $font-xl;
  color: $color-text-title;
  margin-bottom: $space-2;
}

.queue-subtitle {
  font-size: $font-sm;
  color: $color-text-secondary;
  margin-bottom: $space-6;
}

.queue-stats {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: $space-6;
  padding: $space-4 0;
  border-radius: $radius-md;
  background: $brand-50;
  margin-bottom: $space-5;

  .stat {
    display: flex;
    flex-direction: column;
    gap: $space-1;
    min-width: 88px;
  }

  .stat-value {
    font-size: $font-3xl;
    font-weight: $font-weight-bold;
    color: $color-primary;
    line-height: 1;
  }

  .stat-label {
    font-size: $font-xs;
    color: $color-text-secondary;
  }

  .stat-divider {
    width: 1px;
    height: 32px;
    background: $brand-200;
  }
}

.queue-progress {
  margin-bottom: $space-4;
}

.queue-elapsed {
  font-size: $font-xs;
  color: $color-text-secondary;
  margin-bottom: $space-2;
}

.queue-cancel {
  color: $color-text-secondary;

  &:hover {
    color: $color-primary;
  }
}

.queue-fade-enter-active,
.queue-fade-leave-active {
  transition: opacity $transition-base;
}

.queue-fade-enter-from,
.queue-fade-leave-to {
  opacity: 0;
}
</style>
