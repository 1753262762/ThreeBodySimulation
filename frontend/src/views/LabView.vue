<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import ParameterEditor from '../components/ParameterEditor.vue'
import SimulationCanvas from '../components/SimulationCanvas.vue'
import KpiCards from '../components/KpiCards.vue'
import MetricChart from '../components/MetricChart.vue'
import QueuePanel from '../components/QueuePanel.vue'
import ThemeSelector from '../components/ThemeSelector.vue'
import PlaybackTimeline from '../components/PlaybackTimeline.vue'
import EventPanel from '../components/EventPanel.vue'
import WarningModal from '../components/WarningModal.vue'
import { useDraftStore } from '../stores/draft'
import { useExperimentsStore, type MetricSample } from '../stores/experiments'
import { usePreferencesStore, type CameraMode, type ProjectionPlane } from '../stores/preferences'
import { getCanvasPalette } from '../lib/theme'
import { formatScientific, formatSimulationTime } from '../lib/format'
import { fromSi, unitLabel } from '../lib/units'
import type { HoverBodyInfo } from '../lib/canvasHover'
import type { SceneCameraPreset } from '../lib/scene3d'
import {
  LIVE_TRAIL_LIMIT,
  isTerminalStatus,
  type SimulationEvent,
  type SimulationState,
  type ValidationIssue,
} from '../contracts'

const draftStore = useDraftStore()
const experimentsStore = useExperimentsStore()
const preferences = usePreferencesStore()
const router = useRouter()

const SimulationScene3D = defineAsyncComponent(() => import('../components/SimulationScene3D.vue'))
type ViewMode = '2d' | '3d'
interface Scene3DExposed {
  resetCamera(): void
  setCameraPreset(preset: SceneCameraPreset): void
}
const SCENE_3D_PRESETS: readonly SceneCameraPreset[] = ['FREE', 'XY', 'XZ', 'YZ']

const projection = computed(() => preferences.projection)
const apiModeLabel = (import.meta.env.VITE_API_MODE ?? 'live') === 'mock' ? 'Mock · 内存模拟' : 'Live · Java 服务'
const trailVersion = computed(() => experimentsStore.trailVersion)
const trails = computed(() => experimentsStore.trails)

const activeExperiment = computed(() => experimentsStore.current)

const displayState = computed(() => experimentsStore.displayState)
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

/** 每体质量，供质心跟随与悬停质心距离计算。 */
const bodyMasses = computed(() => {
  const map = new Map<string, number>()
  const config = experimentsStore.current?.config
  if (config) {
    for (const body of config.bodies) {
      if (body.id) map.set(body.id, body.massKg)
    }
  }
  return map
})

const currentBodies = computed(() => experimentsStore.current?.config.bodies ?? [])

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
const sidebarCollapsed = ref(false)
const sceneExpanded = ref(false)
const viewMode = ref<ViewMode>('2d')
const scene3dPreset = ref<SceneCameraPreset>('FREE')

const connectionLabel = computed(() => {
  switch (experimentsStore.connectionState) {
    case 'OPEN':
      return '实时连接'
    case 'CONNECTING':
      return '正在连接'
    case 'RECONNECTING':
      return '正在重连'
    case 'CLOSED':
      return '实时连接已断开'
    default:
      if (experimentsStore.backendReachable === true) return '后端已连接'
      if (experimentsStore.backendReachable === false) return '后端不可用'
      return '正在连接后端'
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
      if (experimentsStore.connectionState === 'IDLE' && experimentsStore.backendReachable === true) {
        return 'is-open'
      }
      if (experimentsStore.backendReachable === null) return 'is-pending'
      return 'is-down'
  }
})

function toggleSceneExpanded(): void {
  sceneExpanded.value = !sceneExpanded.value
  requestAnimationFrame(() => fitCanvas())
}

function toggleSidebar(): void {
  sidebarCollapsed.value = !sidebarCollapsed.value
  requestAnimationFrame(() => fitCanvas())
}

function switchSidebarTab(): void {
  activeTab.value = activeTab.value === 'parameters' ? 'queue' : 'parameters'
  sidebarCollapsed.value = false
  requestAnimationFrame(() => fitCanvas())
}

// ---- 提交流：本地校验 → 服务端校验 → Warning 二次确认 → 创建 ----
const warningOpen = ref(false)
const pendingWarnings = ref<ValidationIssue[]>([])

async function applyAndCreate(): Promise<void> {
  const config = draftStore.localConversion.config
  if (!config || !draftStore.canSubmit || draftStore.creating) return
  const result = await draftStore.validateWithServer()
  if (!result) return
  const warnings = result.issues.filter((item) => item.severity === 'WARNING')
  if (warnings.length > 0 && !draftStore.warningsConfirmed) {
    pendingWarnings.value = warnings
    warningOpen.value = true
    return
  }
  await createWithNormalizedConfig()
}

/** 只能使用最近一次校验响应中的 normalizedConfig 创建，确认后草稿变化需重新校验。 */
async function createWithNormalizedConfig(): Promise<void> {
  if (draftStore.creating) return
  if (!draftStore.serverValidation || draftStore.validatedFingerprint !== draftStore.configFingerprint) {
    const result = await draftStore.validateWithServer()
    if (!result) return
    const warnings = result.issues.filter((item) => item.severity === 'WARNING')
    if (warnings.length > 0 && !draftStore.warningsConfirmed) {
      pendingWarnings.value = warnings
      warningOpen.value = true
      return
    }
  }
  const config = draftStore.serverValidation?.normalizedConfig
  if (!config) return
  draftStore.beginCreate()
  try {
    const created = await experimentsStore.createExperiment(config, draftStore.draft.name || undefined)
    if (created) {
      draftStore.markApplied(config)
      draftStore.confirmWarnings()
      await experimentsStore.loadExperiment(created.id)
      experimentsStore.connect(created.id)
      router.push('/experiments/' + created.id)
    }
  } finally {
    draftStore.endCreate()
  }
}

function onWarningConfirm(): void {
  warningOpen.value = false
  draftStore.confirmWarnings()
  void createWithNormalizedConfig()
}

function onWarningCancel(): void {
  warningOpen.value = false
  draftStore.dismissWarnings()
}

function setPlane(plane: ProjectionPlane): void {
  preferences.setProjection(plane)
}

// ---- 观察模式 ----
const CAMERA_MODES: CameraMode[] = ['FREE', 'CENTER_OF_MASS', 'FOLLOW_BODY', 'AUTO_FIT']
const CAMERA_MODE_LABELS: Record<CameraMode, string> = {
  FREE: '自由',
  CENTER_OF_MASS: '质心',
  FOLLOW_BODY: '跟随',
  AUTO_FIT: '适应',
}

function setCameraMode(mode: CameraMode): void {
  if (mode === 'FOLLOW_BODY') {
    const first = experimentsStore.current?.config.bodies.find((body) => body.id)?.id
    if (!first) return
    preferences.setFollowBody(first)
    preferences.setCameraMode('FOLLOW_BODY')
  } else {
    if (preferences.followBodyId) preferences.setFollowBody(null)
    preferences.setCameraMode(mode)
  }
}

function onFollowBodyChange(event: Event): void {
  const value = (event.target as HTMLSelectElement).value
  if (value) {
    preferences.setFollowBody(value)
    preferences.setCameraMode('FOLLOW_BODY')
  } else {
    preferences.setFollowBody(null)
  }
}

/** 切换实验时目标天体不存在则回退自由视角。 */
watch(
  () => experimentsStore.current?.id,
  () => {
    const ids = new Set((experimentsStore.current?.config.bodies ?? []).map((body) => body.id))
    if (preferences.followBodyId && !ids.has(preferences.followBodyId)) {
      preferences.setFollowBody(null)
    }
  },
)

// ---- 画布悬停 Tooltip ----
const hovered = ref<HoverBodyInfo | null>(null)
function onHoverBody(info: HoverBodyInfo | null): void {
  hovered.value = info
}

function centerOfMass(state: SimulationState | null): { x: number; y: number; z: number } | null {
  if (!state || state.bodies.length === 0) return null
  let totalMass = 0
  let cx = 0
  let cy = 0
  let cz = 0
  for (const body of state.bodies) {
    const mass = bodyMasses.value.get(body.id) ?? 0
    if (!(mass > 0)) continue
    totalMass += mass
    cx += body.position.x * mass
    cy += body.position.y * mass
    cz += body.position.z * mass
  }
  if (totalMass <= 0) return null
  return { x: cx / totalMass, y: cy / totalMass, z: cz / totalMass }
}

interface HoverTooltipRow {
  label: string
  value: string
}

const hoverTooltip = computed(() => {
  const info = hovered.value
  if (!info) return null
  const config = experimentsStore.current?.config
  const spec = config?.bodies.find((body) => body.id === info.bodyId)
  const display = experimentsStore.displayState
  const timeSeconds = display?.simulationTimeSeconds ?? experimentsStore.current?.progress.simulationTimeSeconds ?? 0
  const velocity = info.bodyState.velocity
  const speed = Math.hypot(velocity.x, velocity.y, velocity.z)
  const position = info.bodyState.position
  const com = centerOfMass(display)
  const comDistance = com ? Math.hypot(position.x - com.x, position.y - com.y, position.z - com.z) : null
  const system = preferences.unitSystem
  const rows: HoverTooltipRow[] = [
    {
      label: '质量',
      value: formatScientific(fromSi(spec?.massKg ?? 0, 'mass', system)) + ' ' + unitLabel('mass', system),
    },
    {
      label: '速度',
      value: formatScientific(fromSi(speed, 'velocity', system)) + ' ' + unitLabel('velocity', system),
    },
    {
      label: '位置',
      value: '(' + formatScientific(fromSi(position.x, 'length', system)) + ', ' +
        formatScientific(fromSi(position.y, 'length', system)) + ', ' +
        formatScientific(fromSi(position.z, 'length', system)) + ') ' + unitLabel('length', system),
    },
    { label: '时间', value: formatSimulationTime(timeSeconds) },
  ]
  if (comDistance !== null) {
    rows.push({
      label: '质心距离',
      value: formatScientific(fromSi(comDistance, 'length', system)) + ' ' + unitLabel('length', system),
    })
  }
  return {
    anchorX: info.anchorCssX,
    anchorY: info.anchorCssY,
    name: spec?.name ?? info.bodyId,
    color: spec?.color ?? '#35c9a4',
    rows,
  }
})

/** 悬停仅在暂停、终态或回看暂停时启用。 */
const hoverEnabled = computed(() => {
  const status = experimentsStore.current?.status
  if (!status) return false
  if (status === 'PAUSED' || isTerminalStatus(status)) return true
  return experimentsStore.isReviewing && experimentsStore.playbackMode === 'REVIEW_PAUSED'
})

const canvasPalette = computed(() => getCanvasPalette(preferences.resolvedTheme))
const rendererSnapshotBuffer = computed(() => (
  experimentsStore.isReviewing ? null : experimentsStore.snapshotBuffer
))

// ---- 事件面板 ----
function onSelectEvent(event: SimulationEvent): void {
  const id = experimentsStore.current?.id
  if (!id) return
  void experimentsStore.locateEvent(id, event)
}

function encounterDistance(event: SimulationEvent): string {
  const distance = event.closestDistanceMeters ?? event.distanceMeters
  if (distance === null || distance === undefined) return '—'
  return formatScientific(fromSi(distance, 'length', preferences.unitSystem)) + ' ' + unitLabel('length', preferences.unitSystem)
}

const canvasRef = ref<InstanceType<typeof SimulationCanvas> | null>(null)
const scene3dRef = ref<Scene3DExposed | null>(null)
function fitCanvas(): void {
  canvasRef.value?.fitToContent()
}

function setViewMode(mode: ViewMode): void {
  viewMode.value = mode
  hovered.value = null
}

function set3dCameraPreset(preset: SceneCameraPreset): void {
  scene3dPreset.value = preset
  scene3dRef.value?.setCameraPreset(preset)
}

function reset3dCamera(): void {
  scene3dPreset.value = 'FREE'
  scene3dRef.value?.resetCamera()
}

function onDismiss(key: string): void {
  experimentsStore.dismissEncounter(key)
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
  experimentsStore.cancelReplayAndCleanup()
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
      <span class="connection-badge" :class="connectionClass">
        <span class="connection-dot"></span>{{ connectionLabel }}
      </span>
      <div class="lab-header-actions">
        <ThemeSelector />
        <button
          type="button"
          :aria-pressed="sidebarCollapsed"
          @click="toggleSidebar"
        >
          {{ sidebarCollapsed ? '展开侧栏' : '收起侧栏' }}
        </button>
        <button type="button" @click="switchSidebarTab">
          {{ activeTab === 'parameters' ? '切换到队列' : '切换到参数' }}
        </button>
      </div>
    </header>

    <section class="lab-layout" :class="{ 'is-sidebar-collapsed': sidebarCollapsed }">
      <aside v-show="!sidebarCollapsed && activeTab === 'parameters'" class="lab-parameters scrollable">
        <ParameterEditor @apply="applyAndCreate" />
      </aside>
      <aside v-show="!sidebarCollapsed && activeTab === 'queue'" class="lab-parameters scrollable">
        <QueuePanel />
      </aside>

      <section class="lab-main" :class="{ 'is-scene-expanded': sceneExpanded }">
        <article class="lab-scene-card">
          <header>
            <div class="scene-header-left">
              <span>模拟视图</span>
              <div class="scene-mode-toggle" role="group" aria-label="视图维度">
                <button type="button" :class="{ active: viewMode === '2d' }" :aria-pressed="viewMode === '2d'" @click="setViewMode('2d')">2D</button>
                <button type="button" :class="{ active: viewMode === '3d' }" :aria-pressed="viewMode === '3d'" @click="setViewMode('3d')">3D</button>
              </div>
              <small>{{ viewMode === '2d' ? projection + ' 投影' : '空间视图' }} · 最多 {{ LIVE_TRAIL_LIMIT }} 轨迹点/体</small>
            </div>
            <div class="scene-header-right">
              <template v-if="viewMode === '2d'">
                <button :class="{ active: projection === 'XY' }" @click="setPlane('XY')">XY</button>
                <button :class="{ active: projection === 'XZ' }" @click="setPlane('XZ')">XZ</button>
                <button :class="{ active: projection === 'YZ' }" @click="setPlane('YZ')">YZ</button>
              </template>
              <button @click="preferences.toggleTrails()">轨迹 {{ preferences.showTrails ? '开' : '关' }}</button>
              <button v-if="viewMode === '2d'" @click="preferences.toggleLabels()">标签 {{ preferences.showLabels ? '开' : '关' }}</button>
              <button @click="preferences.toggleGrid()">网格 {{ preferences.showGrid ? '开' : '关' }}</button>
              <button v-if="viewMode === '2d'" @click="preferences.togglePerformanceHud()">性能 HUD {{ preferences.showPerformanceHud ? '开' : '关' }}</button>
              <button v-if="viewMode === '2d'" @click="fitCanvas">适应窗口</button>
              <button v-else @click="reset3dCamera">重置相机</button>
              <button
                class="monitor-toggle-button"
                :aria-pressed="sceneExpanded"
                :title="sceneExpanded ? '展开下方监控区' : '收起下方监控区'"
                @click="toggleSceneExpanded"
              >{{ sceneExpanded ? '展开监控区' : '收起监控区' }}</button>
            </div>
          </header>
          <div v-if="viewMode === '2d'" class="scene-camera-bar" role="group" aria-label="观察模式">
            <span class="scene-camera-label">观察</span>
            <button
              v-for="mode in CAMERA_MODES"
              :key="mode"
              type="button"
              class="camera-mode-btn"
              :class="{ active: preferences.cameraMode === mode }"
              :aria-pressed="preferences.cameraMode === mode"
              @click="setCameraMode(mode)"
            >{{ CAMERA_MODE_LABELS[mode] }}</button>
            <select
              v-if="preferences.cameraMode === 'FOLLOW_BODY'"
              class="camera-body-select"
              aria-label="选择跟随天体"
              :value="preferences.followBodyId ?? ''"
              @change="onFollowBodyChange"
            >
              <option v-for="body in currentBodies" :key="body.id ?? body.name" :value="body.id ?? ''">
                {{ body.name }}
              </option>
            </select>
          </div>
          <div v-else class="scene-camera-bar" role="group" aria-label="三维相机视角">
            <span class="scene-camera-label">视角</span>
            <button
              v-for="preset in SCENE_3D_PRESETS"
              :key="preset"
              type="button"
              class="camera-mode-btn"
              :class="{ active: scene3dPreset === preset }"
              :aria-pressed="scene3dPreset === preset"
              @click="set3dCameraPreset(preset)"
            >{{ preset === 'FREE' ? 'Free' : preset }}</button>
          </div>
          <div class="scene-canvas-zone">
            <SimulationCanvas
              v-if="viewMode === '2d'"
              ref="canvasRef"
              :state="displayState"
              :snapshot-buffer="rendererSnapshotBuffer"
              :trails-per-body="trails"
              :trail-version="trailVersion"
              :projection="projection"
              :show-trails="preferences.showTrails"
              :show-labels="preferences.showLabels"
              :show-grid="preferences.showGrid"
              :show-performance-hud="preferences.showPerformanceHud"
              :body-names="bodyNames"
              :body-colors="bodyColors"
              :body-masses="bodyMasses"
              :nearest-pair-ids="nearestPairIds"
              :camera-mode="preferences.cameraMode"
              :follow-body-id="preferences.followBodyId"
              :trail-cutoff-step="experimentsStore.trailCutoffStep"
              :events="experimentsStore.events"
              :selected-event-id="experimentsStore.selectedEventId"
              :hover-enabled="hoverEnabled"
              :palette="canvasPalette"
              @camera-mode-change="preferences.setCameraMode"
              @hover-body="onHoverBody"
            />
            <SimulationScene3D
              v-else
              ref="scene3dRef"
              :experiment-key="activeExperiment?.id ?? null"
              :state="displayState"
              :snapshot-buffer="rendererSnapshotBuffer"
              :trajectories="trails"
              :trail-version="trailVersion"
              :trail-cutoff-step="experimentsStore.trailCutoffStep"
              :show-trails="preferences.showTrails"
              :show-grid="preferences.showGrid"
              :body-colors="bodyColors"
              :palette="canvasPalette"
            />
            <div
              v-if="viewMode === '2d' && hoverTooltip"
              class="body-hover-tooltip"
              data-testid="body-hover-tooltip"
              :style="{ left: hoverTooltip.anchorX + 'px', top: hoverTooltip.anchorY + 'px' }"
            >
              <div class="hover-tooltip-title">
                <span class="hover-tooltip-dot" :style="{ background: hoverTooltip.color }"></span>
                <b>{{ hoverTooltip.name }}</b>
              </div>
              <dl class="hover-tooltip-rows">
                <div v-for="row in hoverTooltip.rows" :key="row.label" class="hover-tooltip-row">
                  <dt>{{ row.label }}</dt>
                  <dd>{{ row.value }}</dd>
                </div>
              </dl>
            </div>
            <div v-if="experimentsStore.encounterAlerts.length > 0" class="encounter-toast-stack">
              <div v-for="(alert) in experimentsStore.encounterAlerts.slice(-3)" :key="alert.key" class="encounter-toast">
                <button @click="onDismiss(alert.key)">×</button>
                近距离事件：{{ encounterDistance(alert.event) }}
              </div>
            </div>
          </div>
          <PlaybackTimeline class="lab-playback" />

        </article>

        <EventPanel
          v-show="!sceneExpanded"
          class="lab-event-panel"
          :events="experimentsStore.events"
          :selected-event-id="experimentsStore.selectedEventId"
          @select="onSelectEvent"
        />

        <KpiCards
          v-show="!sceneExpanded"
          :metrics="metrics"
          :config="activeExperiment?.config ?? null"
          :step="step"
          :simulation-time-seconds="simTime"
        />

        <div v-show="!sceneExpanded" class="chart-grid">
          <MetricChart title="系统能量" subtitle="总能量 (J) vs 步数" :series="energySeries" :paused="sceneExpanded" />
          <MetricChart title="角动量" subtitle="大小 (kg·m²/s)" :series="angularSeries" :paused="sceneExpanded" />
          <MetricChart title="天体间距" subtitle="最近两体距离 (m)" :series="distanceSeries" :paused="sceneExpanded" />
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
                <button class="apply-button" @click="router.push('/reports/' + activeExperiment.id)">查看报告</button>
                <div v-if="experimentsStore.actionError" class="action-error">{{ experimentsStore.actionError }}</div>
              </template>
            </div>
          </article>
        </div>
      </section>
    </section>

    <WarningModal
      :open="warningOpen"
      :warnings="pendingWarnings"
      @confirm="onWarningConfirm"
      @cancel="onWarningCancel"
    />
  </main>
</template>
