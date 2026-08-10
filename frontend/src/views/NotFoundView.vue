<script setup lang="ts">
import { useRouter } from 'vue-router'
const router = useRouter()
</script>

<template>
  <main class="report-page">
    <div class="report-loading">
      <h2>404 · 页面不存在</h2>
      <button @click="router.push('/')">返回参数实验室</button>
    </div>
  </main>
</template>
