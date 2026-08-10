<script setup lang="ts">
import { computed } from 'vue'
import { useExperimentsStore } from '../stores/experiments'

const experimentsStore = useExperimentsStore()

const connectionLabel = computed(() => {
  switch (experimentsStore.connectionState) {
    case 'OPEN':
      return '实时连接'
    case 'CONNECTING':
      return '正在连接'
    case 'RECONNECTING':
      return '正在重连'
    case 'CLOSED':
      return '连接已关闭'
    default:
      return '未连接'
  }
})

const connectionClass = computed(() => {
  switch (experimentsStore.connectionState) {
    case 'OPEN':
      return 'is-open'
    case 'CONNECTING':
    case 'RECONNECTING':
      return 'is-pending'
    default:
      return 'is-down'
  }
})
</script>

<template>
  <header class="app-header">
    <RouterLink class="brand" to="/" aria-label="返回参数实验室">
      <span class="brand-mark">λ</span>
      <span class="brand-copy"><strong>三体动力学实验室</strong><small>THREE-BODY DYNAMICS LAB</small></span>
    </RouterLink>
    <nav class="app-nav" aria-label="主导航">
      <RouterLink to="/">参数实验室</RouterLink>
    </nav>
    <span class="connection-badge" :class="connectionClass">
      <span class="connection-dot"></span>{{ connectionLabel }}
    </span>
  </header>
</template>