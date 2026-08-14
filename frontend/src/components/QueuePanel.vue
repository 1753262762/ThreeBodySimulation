<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useExperimentsStore } from '../stores/experiments'
import { EXPERIMENT_STATUS_LABELS } from '../contracts'
import { formatBytes, formatInteger } from '../lib/format'
import AppTooltip from './AppTooltip.vue'

const store = useExperimentsStore()
const reorderMode = ref(false)
const reorderIds = ref<string[]>([])

const queued = computed(() => store.queued)
const canReorder = computed(() => queued.value.length >= 2)
const healthLabels = {
  GOOD: '可信度良好',
  WARNING: '谨慎解读',
  POOR: '误差明显',
  FAILED: '计算失败',
} as const

function toggleReorder(): void {
  if (!canReorder.value) return
  reorderMode.value = !reorderMode.value
  if (reorderMode.value) {
    reorderIds.value = [...queued.value.map((item) => item.id)]
  }
}

watch(
  () => queued.value.map((item) => item.id).join('\u0000'),
  (ids) => {
    if (!reorderMode.value) return
    if (!canReorder.value || ids !== reorderIds.value.join('\u0000')) {
      reorderMode.value = false
      reorderIds.value = []
    }
  },
)

function moveId(id: string, direction: -1 | 1): void {
  const index = reorderIds.value.indexOf(id)
  const target = index + direction
  if (index < 0 || target < 0 || target >= reorderIds.value.length) return
  const next = [...reorderIds.value]
  const [moved] = next.splice(index, 1)
  next.splice(target, 0, moved)
  reorderIds.value = next
}

async function confirmReorder(): Promise<void> {
  const ids = [...reorderIds.value]
  const others = store.summaries
    .filter((item) => item.status !== 'QUEUED')
    .sort((a, b) => (a.queuePosition ?? 0) - (b.queuePosition ?? 0))
    .map((item) => item.id)
  const full = [...ids, ...others]
  const ok = await store.reorderQueue(full)
  if (ok) reorderMode.value = false
}
</script>

<template>
  <div class="queue-panel">
    <div class="lab-title">
      <span>任务队列</span>
      <AppTooltip
        :text="canReorder ? '调整等待任务顺序' : '至少需要两个等待任务'"
        :focusable="!canReorder"
      >
        <button
          class="queue-reorder-toggle"
          :class="{ 'is-active': reorderMode }"
          type="button"
          :disabled="!canReorder"
          :aria-pressed="reorderMode"
          @click="toggleReorder"
        >{{ reorderMode ? '完成排序' : '调整顺序' }}</button>
      </AppTooltip>
    </div>

    <div v-if="reorderMode" class="reorder-box">
      <div v-for="(id, index) in reorderIds" :key="id" class="reorder-row">
        <span class="queue-position">{{ index + 1 }}</span>
        <span class="queue-name">{{ store.summaries.find((item) => item.id === id)?.name ?? id }}</span>
        <button type="button" @click="moveId(id, -1)" :disabled="index === 0">↑</button>
        <button type="button" @click="moveId(id, 1)" :disabled="index === reorderIds.length - 1">↓</button>
      </div>
      <button class="apply-button compact" type="button" @click="confirmReorder">提交新顺序</button>
    </div>

    <template v-else>
      <div v-if="store.summaries.length === 0" class="queue-empty">暂无实验，先在左侧应用参数创建实验。</div>
      <div
        v-for="item in store.summaries"
        :key="item.id"
        class="queue-item"
        :class="{
          'is-running': item.status === 'RUNNING',
          'is-completed': item.status === 'COMPLETED' || item.status === 'CANCELLED',
          'is-failed': item.status === 'FAILED',
        }"
      >
        <div class="queue-item-row">
          <span class="queue-position">
            {{ item.status === 'QUEUED' || item.status === 'RUNNING' ? (item.queuePosition ?? 0) + 1 : '—' }}
          </span>
          <RouterLink class="queue-name" :to="'/experiments/' + item.id" :title="item.name">{{ item.name }}</RouterLink>
          <span class="queue-status">{{ EXPERIMENT_STATUS_LABELS[item.status] }}</span>
          <span v-if="item.healthStatus" class="health-badge" :data-health="item.healthStatus">{{ healthLabels[item.healthStatus] }}</span>
        </div>
        <div class="queue-meta">
          <span v-if="item.lineage">第 {{ item.lineage.retryDepth }} 次对照实验</span>
          <span>{{ item.bodyCount }} 体</span>
          <span>步骤 {{ formatInteger(item.progress.step) }}</span>
          <span v-if="item.progress.completionRatio !== null && item.progress.completionRatio !== undefined">
            {{ Math.round(item.progress.completionRatio * 100) }}%
          </span>
          <span v-if="item.storageBytes">{{ formatBytes(item.storageBytes) }}</span>
        </div>
        <div class="queue-progress">
          <i :style="{ width: Math.min(100, Math.round((item.progress.completionRatio ?? 0) * 100)) + '%' }"></i>
        </div>
        <div class="queue-actions">
          <button v-if="item.status === 'QUEUED'" type="button" @click="store.submitActionFor(item.id, 'CANCEL')">取消</button>
          <button v-if="item.status === 'RUNNING'" type="button" @click="store.submitActionFor(item.id, 'PAUSE')">暂停</button>
          <button v-if="item.status === 'PAUSED'" type="button" @click="store.submitActionFor(item.id, 'RESUME')">继续</button>
          <button v-if="item.status === 'PAUSED'" type="button" @click="store.submitActionFor(item.id, 'STEP')">单步</button>
          <button type="button" class="danger" @click="store.deleteExperiment(item.id)">删除</button>
          <RouterLink v-if="item.status === 'COMPLETED'" :to="'/reports/' + item.id">报告</RouterLink>
        </div>
      </div>
    </template>

    <div class="queue-storage">占用空间：{{ formatBytes(store.totalStorageBytes) }}</div>
    <div v-if="store.actionError" class="action-error">{{ store.actionError }}</div>
  </div>
</template>
