<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import ParameterEditor from '../components/ParameterEditor.vue'
import SimulationCanvas from '../components/SimulationCanvas.vue'
import KpiCards from '../components/KpiCards.vue'
import MetricChart from '../components/MetricChart.vue'
import QueuePanel from '../components/QueuePanel.vue'
import { useDraftStore } from '../stores/draft'
import { useExperimentsStore, type MetricSample } from '../stores/experiments'
import { usePreferencesStore, type ProjectionPlane } from '../stores/preferences'
import { LIVE_TRAIL_LIMIT } from '../contracts'

const draftStore = useDraftStore()
const experimentsStore = useExperimentsStore()
const preferences = usePreferencesStore()
const router = useRouter()

const projection = computed(() => preferences.projection)
const apiModeLabel = (import.meta.env.VITE_API_MODE ?? 'live') === 'mock' ? 'Mock · 内存模拟' : 'Live · Java 服务'
const trailVersion = computed(() => experimentsStore.trailVersion)
const trails = computed(() => experimentsStore.trails)

const activeExperiment = computed(() => experimentsStore.current)

const state = computed(() => experimentsStore.liveState)
const metrics = computed(() => experimentsStore.liveMetrics)
const metricSamples = computed<MetricSample[]>(() => experimentsStore.metricSamples)

const step = computed(() => experimentsStore.liveState?.step ?? experimentsStore.current?.progress.step ?? 0)
const simTime = computed(() => experimentsStore.liveState?.simulationTimeSeconds ?? experimentsStore.current?.progress.simulationTimeSeconds ?? 0)

const bodyNames = computed(() => {
  const map = new Map<string, string>()
  const config = experimentsStore.current?.config
  if (config) {
    for (const body of config.bodies) {
      if (body.id) map.set(body.id, body.name)
    }
  }
  return map
})

const bodyColors = computed(() => {
  const map = new Map<string, string>()
  const config = experimentsStore.current?.config
  if (config) {
    for (const body of config.bodies) {
      if (body.id && body.color) map.set(body.id, body.color)
    }
  }
  return map
})

const nearestPairIds = computed(() => metrics.value?.minimumPairBodyIds ?? null)

const energySeries = computed(() => [{
  name: '总能量',
  unit: 'J',
  color: '#35c9a4',
  data: metricSamples.value.map((s) => [s.step, s.totalEnergyJoules] as [number, number]),
}])

const angularSeries = computed(() => [{
  name: '角动量大小',
  unit: 'kg·m²/s',
  color: '#60a5fa',
  data: metricSamples.value.map((s) => [s.step, s.angularMomentumMagnitude] as [number, number]),
}])

const distanceSeries = computed(() => [{
  name: '最近间距',
  unit: 'm',
  color: '#ef6a7a',
  data: metricSamples.value.map((s) => [s.step, s.minimumPairDistanceMeters] as [number, number]),
}])

const activeTab = ref<'parameters' | 'queue'>('parameters')

async function applyAndCreate(): Promise<void> {
  const config = draftStore.localConversion.config
  if (!config) return
  const valid = await draftStore.validateWithServer()
  if (!valid) return
  const created = await experimentsStore.createExperiment(config, draftStore.draft.name || undefined)
  if (created) {
    draftStore.markApplied(config)
    await experimentsStore.loadExperiment(created.id)
    experimentsStore.connect(created.id)
    router.push(`/experiments/${created.id}`)
  }
}

function setPlane(plane: ProjectionPlane): void {
  preferences.setProjection(plane)
}

const canvasRef = ref<InstanceType<typeof SimulationCanvas> | null>(null)
function fitCanvas(): void {
  canvasRef.value?.fitToContent()
}

function onDismiss(index: number): void {
  experimentsStore.dismissEncounter(index)
}

let pollTimer: ReturnType<typeof setInterval> | null = null
function startPolling(): void {
  if (pollTimer) return
  pollTimer = setInterval(() => {
    // 运行中或暂停时周期性刷新队列状态，避免因 STATUS 消息丢失而显示过期快照。
    if (experimentsStore.running || experimentsStore.queued.length > 0) {
      void experimentsStore.loadList()
    }
  }, 3000)
}
startPolling()

onUnmounted(() => {
  experimentsStore.disconnect()
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})
</script>

<template>
  <main class="lab-page">
    <header class="lab-header">
      <span class="lab-logo">λ</span>
      <div class="lab-brand-copy">
        <strong>三体动力学实验室</strong>
        <small>THREE-BODY DYNAMICS LAB</small>
      </div>
      <div class="experiment-name">
        <small>当前模式</small>
        <b>{{ apiModeLabel }}</b>
      </div>
      <div class="lab-header-actions">
        <button @click="activeTab = activeTab === 'parameters' ? 'queue' : 'parameters'">
          {{ activeTab === 'parameters' ? '切换到队列' : '切换到参数' }}
        </button>
      </div>
    </header>

    <section class="lab-layout">
      <aside v-show="activeTab === 'parameters'" class="lab-parameters scrollable">
        <ParameterEditor @apply="applyAndCreate" />
      </aside>
      <aside v-show="activeTab === 'queue'" class="lab-parameters scrollable">
        <QueuePanel />
      </aside>

      <section class="lab-main">
        <article class="lab-scene-card">
          <header>
            <div class="scene-header-left">
              <span>模拟视图</span>
              <small>{{ projection }} 投影 · 最多 {{ LIVE_TRAIL_LIMIT }} 轨迹点/体</small>
            </div>
            <div class="scene-header-right">
              <button :class="{ active: projection === 'XY' }" @click="setPlane('XY')">XY</button>
              <button :class="{ active: projection === 'XZ' }" @click="setPlane('XZ')">XZ</button>
              <button :class="{ active: projection === 'YZ' }" @click="setPlane('YZ')">YZ</button>
              <button @click="preferences.toggleTrails()">轨迹 {{ preferences.showTrails ? '开' : '关' }}</button>
              <button @click="preferences.toggleLabels()">标签 {{ preferences.showLabels ? '开' : '关' }}</button>
              <button @click="preferences.toggleGrid()">网格 {{ preferences.showGrid ? '开' : '关' }}</button>
              <button @click="fitCanvas">适应窗口</button>
            </div>
          </header>
          <SimulationCanvas
            ref="canvasRef"
            :state="state"
            :trails-per-body="trails"
            :trail-version="trailVersion"
            :projection="projection"
            :show-trails="preferences.showTrails"
            :show-labels="preferences.showLabels"
            :show-grid="preferences.showGrid"
            :body-names="bodyNames"
            :body-colors="bodyColors"
            :nearest-pair-ids="nearestPairIds"
          />
          <footer>
            <button :disabled="!experimentsStore.can('PAUSE')" @click="experimentsStore.submitAction('PAUSE')" title="暂停">Ⅱ</button>
            <button class="pause" :disabled="!experimentsStore.can('RESUME')" @click="experimentsStore.submitAction('RESUME')" title="继续">▶</button>
            <button :disabled="!experimentsStore.can('STEP')" @click="experimentsStore.submitAction('STEP')" title="单步">▶│</button>
            <div class="lab-progress">
              <i :style="{ width: `${Math.min(100, Math.round((activeExperiment?.progress.completionRatio ?? 0) * 100))}%` }"></i>
            </div>
            <span>步骤 {{ step.toLocaleString() }}</span>
          </footer>

          <div v-for="(alert, index) in experimentsStore.encounterAlerts.slice(-3)" :key="index" class="encounter-toast">
            <button @click="onDismiss(index)">×</button>
            近距离事件：{{ (alert.distanceMeters / 1e9).toFixed(3) }} × 10⁹ m
          </div>
        </article>

        <KpiCards :metrics="metrics" :step="step" :simulation-time-seconds="simTime" />

        <div class="chart-row">
          <MetricChart title="系统能量" subtitle="总能量 (J) vs 步数" :series="energySeries" />
          <MetricChart title="天体间距" subtitle="最近两体距离 (m)" :series="distanceSeries" />
        </div>
        <div class="chart-row">
          <MetricChart title="角动量" subtitle="大小 (kg·m²/s)" :series="angularSeries" />
          <article class="chart-card">
            <header><b>实验控制</b><span>动作状态</span></header>
            <div class="chart-body" style="padding: 12px; display: grid; gap: 8px; align-content: start;">
              <div v-if="!activeExperiment" class="queue-empty">选择一个实验以查看控制按钮。</div>
              <template v-else>
                <div class="queue-meta">
                  <span>状态：{{ activeExperiment.status }}</span>
                </div>
                <div class="queue-actions">
                  <button v-if="experimentsStore.can('PAUSE')" @click="experimentsStore.submitAction('PAUSE')">暂停</button>
                  <button v-if="experimentsStore.can('RESUME')" @click="experimentsStore.submitAction('RESUME')">继续</button>
                  <button v-if="experimentsStore.can('STEP')" @click="experimentsStore.submitAction('STEP')">单步</button>
                  <button v-if="experimentsStore.can('RESTART')" @click="experimentsStore.submitAction('RESTART')">重启</button>
                  <button v-if="experimentsStore.can('CANCEL')" class="danger" @click="experimentsStore.submitAction('CANCEL')">取消</button>
                </div>
                <button class="apply-button" @click="router.push(`/reports/${activeExperiment.id}`)">查看报告</button>
                <div v-if="experimentsStore.actionError" class="action-error">{{ experimentsStore.actionError }}</div>
              </template>
            </div>
          </article>
        </div>
      </section>
    </section>
  </main>
</template>
