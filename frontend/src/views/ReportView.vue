<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../lib/apiClient'
import type { ReportData } from '../contracts'
import { formatBytes, formatDateTime, formatInteger, formatScientific, formatSimulationTime, formatWallClock } from '../lib/format'
import { EXPERIMENT_STATUS_LABELS, END_REASON_LABELS } from '../contracts'
import ThemeSelector from '../components/ThemeSelector.vue'

const route = useRoute()
const router = useRouter()
const report = ref<ReportData | null>(null)
const loading = ref(true)
const error = ref('')

const experimentId = () => String(route.params.id)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    report.value = await api.getReportData(experimentId())
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载报告失败。'
  } finally {
    loading.value = false
  }
}

function downloadJson(): void {
  if (!report.value) return
  const blob = new Blob([JSON.stringify(report.value, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `report-${experimentId()}.json`
  link.click()
  URL.revokeObjectURL(url)
}

async function downloadCsv(): Promise<void> {
  try {
    const result = await api.exportTrajectory(experimentId())
    const blob = new Blob([result.csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = result.filename
    link.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '下载轨迹失败。'
  }
}

function downloadConfig(): void {
  if (!report.value) return
  const blob = new Blob([JSON.stringify(report.value.experiment.config, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `config-${experimentId()}.json`
  link.click()
  URL.revokeObjectURL(url)
}

const exp = computed(() => report.value?.experiment)
const sampleStride = computed(() => report.value?.sampleStride)
const events = computed(() => report.value?.experiment.events ?? [])
const summary = computed(() => report.value?.experiment.metrics)

onMounted(() => {
  void load()
})
</script>

<template>
  <main class="report-page">
    <div class="report-header">
      <button @click="router.back()">← 返回</button>
      <h1>实验报告 · {{ exp?.name ?? experimentId() }}</h1>
      <div class="report-actions">
        <ThemeSelector />
        <button @click="downloadConfig">配置 JSON</button>
        <button @click="downloadCsv">轨迹 CSV</button>
        <button @click="downloadJson">报告数据</button>
        <button class="primary" onclick="window.print()">打印 / PDF</button>
      </div>
    </div>

    <div v-if="loading" class="report-loading">正在加载报告数据…</div>
    <div v-else-if="error" class="action-error">{{ error }}</div>

    <template v-else>
      <section class="report-section">
        <h2>基本信息</h2>
        <div class="report-grid">
          <article><span>实验 ID</span><b>{{ exp?.id }}</b></article>
          <article><span>状态</span><b>{{ exp ? EXPERIMENT_STATUS_LABELS[exp.status] : '—' }}</b></article>
          <article><span>结束原因</span><b>{{ exp?.endReason ? END_REASON_LABELS[exp.endReason] : '—' }}</b></article>
          <article><span>天体数量</span><b>{{ exp?.bodyCount ?? '—' }}</b></article>
          <article><span>创建时间</span><b>{{ formatDateTime(exp?.createdAt) }}</b></article>
          <article><span>完成时间</span><b>{{ formatDateTime(exp?.completedAt) }}</b></article>
          <article><span>占用空间</span><b>{{ formatBytes(exp?.storageBytes) }}</b></article>
          <article><span>事件总数</span><b>{{ events.length }}</b></article>
        </div>
      </section>

      <section class="report-section">
        <h2>关键指标摘要</h2>
        <div class="report-grid">
          <article><span>总能量</span><b>{{ summary ? formatScientific(summary.totalEnergyJoules) + ' J' : '—' }}</b></article>
          <article><span>相对能量漂移</span><b>{{ summary ? (summary.relativeEnergyDrift * 100).toFixed(4) + ' %' : '—' }}</b></article>
          <article><span>角动量大小</span><b>{{ summary ? formatScientific(summary.angularMomentumMagnitude) + ' kg·m²/s' : '—' }}</b></article>
          <article><span>最小两体距离</span><b>{{ summary ? formatScientific(summary.allTimeMinimumPairDistanceMeters ?? 0) + ' m' : '—' }}</b></article>
          <article><span>累计步数</span><b>{{ formatInteger(exp?.progress.step) }}</b></article>
          <article><span>模拟时间</span><b>{{ formatSimulationTime(exp?.progress.simulationTimeSeconds) }}</b></article>
          <article><span>积分速率</span><b>{{ summary?.stepsPerSecond ? summary.stepsPerSecond.toFixed(1) + ' step/s' : '—' }}</b></article>
          <article><span>墙钟耗时</span><b>{{ formatWallClock(summary?.elapsedWallClockSeconds) }}</b></article>
        </div>
      </section>

      <section class="report-section">
        <h2>采样说明</h2>
        <div class="sampling-note">
          <p>本报告使用 <strong>分层采样（LAYERED）</strong> 策略：达到 <code>{{ exp?.trajectory.pointLimit?.toLocaleString() }}</code> 点上限后，
          保留首尾与事件点并将普通点采样步长加倍。</p>
          <p>当前采样步长（步/点）：<code>{{ sampleStride }}</code>，
          总采样点数：<code>{{ exp?.trajectory.sampleCount?.toLocaleString() }}</code>，
          事件点保留：<code>是</code>。</p>
          <p>报告数据与轨迹 CSV 均采用相同采样，单位制为 SI。</p>
        </div>
      </section>

      <section class="report-section">
        <h2>事件记录</h2>
        <table class="report-table">
          <thead>
            <tr><th>#</th><th>类型</th><th>步数</th><th>模拟时间</th><th>时间</th><th>说明</th></tr>
          </thead>
          <tbody>
            <tr v-for="ev in events" :key="ev.sequence">
              <td>{{ ev.sequence }}</td>
              <td><code>{{ ev.type }}</code></td>
              <td>{{ formatInteger(ev.step) }}</td>
              <td>{{ formatSimulationTime(ev.simulationTimeSeconds) }}</td>
              <td>{{ formatDateTime(ev.timestamp) }}</td>
              <td>{{ ev.message }}</td>
            </tr>
            <tr v-if="events.length === 0">
              <td colspan="6" class="queue-empty">暂无事件。</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section class="report-section">
        <h2>天体初始条件</h2>
        <table class="report-table">
          <thead>
            <tr><th>名称</th><th>颜色</th><th>质量 (kg)</th><th>位置 (m)</th><th>速度 (m/s)</th></tr>
          </thead>
          <tbody>
            <tr v-for="body in exp?.config.bodies" :key="body.id">
              <td>{{ body.name }}</td>
              <td><code>{{ body.color }}</code></td>
              <td>{{ formatScientific(body.massKg) }}</td>
              <td>{{ formatScientific(body.position.x) }}, {{ formatScientific(body.position.y) }}, {{ formatScientific(body.position.z) }}</td>
              <td>{{ formatScientific(body.velocity.x) }}, {{ formatScientific(body.velocity.y) }}, {{ formatScientific(body.velocity.z) }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>
  </main>
</template>

