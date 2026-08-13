<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import type { SimulationEvent, SimulationState } from '../contracts'
import type { CameraMode, ProjectionPlane } from '../stores/preferences'
import type { CanvasPalette } from '../lib/theme'
import type { HoverBodyInfo } from '../lib/canvasHover'
import { formatScientific } from '../lib/format'
import { SnapshotBuffer } from '../lib/snapshotBuffer'
import { BodyTrajectoryBuffer, TrajectoryBuffer } from '../lib/trajectoryBuffer'
import { AdaptiveQualityController } from '../lib/renderQuality'
import { projectGridPoint, visibleGridBounds } from '../lib/gridProjection'
import { simplifyFlatPolylineIndices, trailRenderPolicy } from '../lib/trailSampling'
import { fitCanvasView, resizeCanvasView } from '../lib/canvasViewport'

type LegacyTrailCollection = Map<string, number[] | BodyTrajectoryBuffer>
type TrailCollection = TrajectoryBuffer | LegacyTrailCollection

/**
 * Canvas 三投影渲染器。
 *
 * 背景、轨迹和动态天体使用三个独立 Canvas，共享同一套尺寸与视图
 * 变换。轨迹层按自适应频率重建真实点 Path2D，天体层保持高刷新插值。
 *
 * 观察模式：
 * - FREE：滚轮缩放、拖拽平移、双击适应；手动拖拽自动切回 FREE。
 * - CENTER_OF_MASS / FOLLOW_BODY：只更新平移（平滑接近目标），保留缩放。
 * - AUTO_FIT：以当前天体与游标前可见轨迹包围盒为目标，20% 边距、12 Hz 节流、迟滞与平滑。
 *
 * 轨迹投影只含 scale（不含 offset），平移通过 Canvas transform 复用路径，
 * 纯跟随平移不重投影 8,000×N 点。
 */
const props = withDefaults(
  defineProps<{
    state: SimulationState | null
    /** Optional display-only two-frame reader; state remains authoritative. */
    snapshotBuffer?: SnapshotBuffer | null
    trailsPerBody: TrailCollection
    trailVersion: number
    projection: ProjectionPlane
    showTrails: boolean
    showLabels: boolean
    showGrid: boolean
    showPerformanceHud?: boolean
    bodyNames: Map<string, string>
    bodyColors: Map<string, string>
    /** 天体质量，用于质心跟随与悬停质心距离。 */
    bodyMasses?: Map<string, number>
    nearestPairIds: string[] | null
    cameraMode?: CameraMode
    followBodyId?: string | null
    /** 历史轨迹只绘制 step <= trailCutoffStep 的部分，防止泄露未来轨迹。 */
    trailCutoffStep?: number
    events?: SimulationEvent[]
    selectedEventId?: string | null
    /** 悬停命中仅在暂停/终态/回看暂停时启用。 */
    hoverEnabled?: boolean
    palette?: CanvasPalette
  }>(),
  {
    snapshotBuffer: null,
    showPerformanceHud: false,
    bodyMasses: () => new Map(),
    cameraMode: 'FREE',
    followBodyId: null,
    trailCutoffStep: Number.POSITIVE_INFINITY,
    events: () => [],
    selectedEventId: null,
    hoverEnabled: false,
    palette: undefined,
  },
)

const emit = defineEmits<{
  (e: 'camera-mode-change', mode: CameraMode): void
  (e: 'hover-body', info: HoverBodyInfo | null): void
}>()

const backgroundCanvasRef = ref<HTMLCanvasElement | null>(null)
const trailCanvasRef = ref<HTMLCanvasElement | null>(null)
const dynamicCanvasRef = ref<HTMLCanvasElement | null>(null)
const wrapRef = ref<HTMLDivElement | null>(null)
const backgroundContext = shallowRef<CanvasRenderingContext2D | null>(null)
const trailContext = shallowRef<CanvasRenderingContext2D | null>(null)
const dynamicContext = shallowRef<CanvasRenderingContext2D | null>(null)

// 视图变换：scale 为每米像素数（通常 1e-10 量级），offset 为物理像素偏移。
const view = shallowRef({ scale: 1e-10, offsetX: 0, offsetY: 0 })
const dragging = shallowRef({ active: false, startX: 0, startY: 0, origOffsetX: 0, origOffsetY: 0 })
const quality = new AdaptiveQualityController({
  devicePixelRatio: typeof window === 'undefined' ? 1 : window.devicePixelRatio,
})

let rafId: number | null = null
let dpr = quality.effectiveDpr
let visible = true
let backgroundDirty = true
let trailDirty = true
let dynamicDirty = true
let resizeObserver: ResizeObserver | null = null
let lastTrailDrawMs = Number.NEGATIVE_INFINITY
let canvasSizeInitialized = false

interface CachedTrailPath {
  coordinates: Float64Array
  pointCount: number
  retainedIndices: number[]
  path: Path2D | null
  /** 影响投影与抽稀结果的完整缓存签名。 */
  signature: string
}

const trailPathCache = new Map<string, CachedTrailPath>()

let cachedHudText = ''
let cachedHudKey = ''
let lastHudFormatMs = Number.NEGATIVE_INFINITY

// ---- 相机跟随状态 ----
let lastAutoFitMs = Number.NEGATIVE_INFINITY
let autoFitTarget = { scale: 1e-10, offsetX: 0, offsetY: 0 }
/** 上一次 AUTO_FIT 缩放，用于迟滞判断。 */
let autoFitLastScale = 1e-10
/** 悬停命中结果缓存。 */
let hoverInfo: HoverBodyInfo | null = null
let hoverPointerClient: { x: number; y: number } | null = null

function displayNowMs(): number {
  if (typeof performance !== 'undefined' && Number.isFinite(performance.now())) return performance.now()
  return Date.now()
}

function displayState(atTimeMs = displayNowMs()): SimulationState | null {
  return props.snapshotBuffer?.readInterpolated(atTimeMs) ?? props.state
}

const projectionLabel = computed(() => `${props.projection} 投影视图`)

function palette(): CanvasPalette {
  return props.palette ?? {
    background: '#05080f',
    gridLine: 'rgba(111, 137, 168, 0.22)',
    axisLine: 'rgba(111, 137, 168, 0.5)',
    hudText: 'rgba(141, 149, 162, 0.85)',
  }
}

function invalidateView(): void {
  backgroundDirty = true
  trailDirty = true
  dynamicDirty = true
  wake()
}

function invalidateBackground(): void {
  backgroundDirty = true
  wake()
}

function invalidateTrails(): void {
  trailDirty = true
  wake()
}

function invalidateDynamic(): void {
  dynamicDirty = true
  wake()
}

function shouldAnimate(atTimeMs = displayNowMs()): boolean {
  // A state-only mount draws once. Once a snapshot buffer is active, rAF runs
  // only while the delayed display clock has not reached the current frame.
  return visible && props.snapshotBuffer?.hasPendingInterpolation(atTimeMs) === true
}

function wake(): void {
  if (!visible || rafId !== null || typeof requestAnimationFrame !== 'function') return
  rafId = requestAnimationFrame(renderFrame)
}

function resize(): void {
  const wrap = wrapRef.value
  const backgroundCanvas = backgroundCanvasRef.value
  const trailCanvas = trailCanvasRef.value
  const dynamicCanvas = dynamicCanvasRef.value
  if (!wrap || !backgroundCanvas || !trailCanvas || !dynamicCanvas) return
  const previousDpr = dpr
  const previousSize = { width: dynamicCanvas.width, height: dynamicCanvas.height }
  quality.setDevicePixelRatio(typeof window === 'undefined' ? 1 : window.devicePixelRatio)
  dpr = quality.effectiveDpr
  const rect = wrap.getBoundingClientRect()
  const width = Math.max(1, Math.floor(rect.width * dpr))
  const height = Math.max(1, Math.floor(rect.height * dpr))
  if (canvasSizeInitialized && (width !== previousSize.width || height !== previousSize.height || dpr !== previousDpr)) {
    view.value = resizeCanvasView(
      view.value,
      previousSize,
      { width, height },
      previousDpr,
      dpr,
    )
  }
  for (const canvas of [backgroundCanvas, trailCanvas, dynamicCanvas]) {
    canvas.width = width
    canvas.height = height
    canvas.style.width = `${rect.width}px`
    canvas.style.height = `${rect.height}px`
  }
  canvasSizeInitialized = true
  invalidateView()
  refreshHover()
}

function projectWorldPoint(x: number, y: number, z: number): { horizontal: number; vertical: number } {
  switch (props.projection) {
    case 'XY': return { horizontal: x, vertical: y }
    case 'XZ': return { horizontal: x, vertical: z }
    case 'YZ': return { horizontal: y, vertical: z }
  }
}

function project(x: number, y: number, z: number): [number, number] {
  const v = view.value
  switch (props.projection) {
    case 'XY': return [x * v.scale + v.offsetX, -y * v.scale + v.offsetY]
    case 'XZ': return [x * v.scale + v.offsetX, -z * v.scale + v.offsetY]
    case 'YZ': return [y * v.scale + v.offsetX, -z * v.scale + v.offsetY]
  }
}

function fitToContent(): void {
  const canvas = dynamicCanvasRef.value
  const state = displayState()
  if (!canvas || !state || state.bodies.length === 0) return
  const fitted = fitCanvasView(
    state.bodies.map((body) => projectWorldPoint(body.position.x, body.position.y, body.position.z)),
    { width: canvas.width, height: canvas.height },
  )
  if (!fitted) return
  view.value = fitted
  autoFitLastScale = fitted.scale
  autoFitTarget = { ...fitted }
  invalidateView()
  refreshHover()
}

function drawGrid(ctx: CanvasRenderingContext2D): void {
  if (!props.showGrid) return
  const canvas = backgroundCanvasRef.value
  if (!canvas) return
  const v = view.value
  ctx.strokeStyle = palette().gridLine
  ctx.lineWidth = 1

  const targetPixels = 60 * dpr
  const worldSpacing = targetPixels / Math.max(1e-30, Math.abs(v.scale))
  const magnitude = 10 ** Math.floor(Math.log10(worldSpacing))
  const factor = worldSpacing / magnitude
  let step = magnitude
  if (factor > 5) step = magnitude * 10
  else if (factor > 2) step = magnitude * 5
  else if (factor > 1) step = magnitude * 2

  const bounds = visibleGridBounds({
    width: canvas.width,
    height: canvas.height,
    scale: v.scale,
    offsetX: v.offsetX,
    offsetY: v.offsetY,
  })
  const leftWorld = Math.min(bounds.left, bounds.right)
  const rightWorld = Math.max(bounds.left, bounds.right)
  const topWorld = Math.min(bounds.top, bounds.bottom)
  const bottomWorld = Math.max(bounds.top, bounds.bottom)

  ctx.beginPath()
  for (let x = Math.floor(leftWorld / step) * step; x <= rightWorld; x += step) {
    const [px] = projectGridPoint(x, 0, {
      width: canvas.width,
      height: canvas.height,
      scale: v.scale,
      offsetX: v.offsetX,
      offsetY: v.offsetY,
    })
    ctx.moveTo(px, 0); ctx.lineTo(px, canvas.height)
  }
  for (let y = Math.floor(topWorld / step) * step; y <= bottomWorld; y += step) {
    const [, py] = projectGridPoint(0, y, {
      width: canvas.width,
      height: canvas.height,
      scale: v.scale,
      offsetX: v.offsetX,
      offsetY: v.offsetY,
    })
    ctx.moveTo(0, py); ctx.lineTo(canvas.width, py)
  }
  ctx.stroke()

  ctx.strokeStyle = palette().axisLine
  ctx.lineWidth = 1
  ctx.beginPath()
  const [yAxisX, xAxisY] = projectGridPoint(0, 0, {
    width: canvas.width,
    height: canvas.height,
    scale: v.scale,
    offsetX: v.offsetX,
    offsetY: v.offsetY,
  })
  ctx.moveTo(0, xAxisY); ctx.lineTo(canvas.width, xAxisY)
  ctx.moveTo(yAxisX, 0); ctx.lineTo(yAxisX, canvas.height)
  ctx.stroke()
}

function updateCachedTrailPath(cached: CachedTrailPath, pointCount: number): CachedTrailPath {
  const policy = trailRenderPolicy(quality.quality)
  const retainedIndices = simplifyFlatPolylineIndices(
    cached.coordinates,
    pointCount,
    policy.toleranceCssPx * dpr,
  )
  let path: Path2D | null = null
  if (typeof Path2D !== 'undefined' && retainedIndices.length > 0) {
    path = new Path2D()
    const first = retainedIndices[0]
    path.moveTo(cached.coordinates[first * 2], cached.coordinates[first * 2 + 1])
    for (let i = 1; i < retainedIndices.length; i += 1) {
      const index = retainedIndices[i]
      path.lineTo(cached.coordinates[index * 2], cached.coordinates[index * 2 + 1])
    }
  }
  cached.pointCount = pointCount
  cached.retainedIndices = retainedIndices
  cached.path = path
  return cached
}

function reusableTrailCache(bodyId: string, requiredPointCount: number): CachedTrailPath {
  const current = trailPathCache.get(bodyId)
  if (current && current.coordinates.length >= requiredPointCount * 2) return current
  return {
    coordinates: new Float64Array(Math.max(2, requiredPointCount * 2)),
    pointCount: 0,
    retainedIndices: [],
    path: null,
    signature: '',
  }
}

function trailCacheSignature(cutoffStep: number): string {
  return [
    props.trailVersion,
    props.projection,
    cutoffStep,
    view.value.scale,
    dpr,
    quality.quality,
  ].join(':')
}

function projectWorld(horizontal: number, vertical: number, scale: number): [number, number] {
  return [horizontal * scale, -vertical * scale]
}

function projectRingTrail(
  bodyId: string,
  points: BodyTrajectoryBuffer,
  cutoffStep: number,
): CachedTrailPath {
  const cached = reusableTrailCache(bodyId, points.capacity)
  const v = view.value
  const signature = trailCacheSignature(cutoffStep)
  if (cached.signature === signature) return cached
  const coordinates = cached.coordinates
  const projection = props.projection
  let written = 0
  points.forEachCoordinates((step, x, y, z) => {
    if (step > cutoffStep) return
    const horizontal = projection === 'YZ' ? y : x
    const vertical = projection === 'XY' ? y : z
    const [px, py] = projectWorld(horizontal, vertical, v.scale)
    coordinates[written * 2] = px
    coordinates[written * 2 + 1] = py
    written += 1
  })
  cached.signature = signature
  return updateCachedTrailPath(cached, written)
}

function projectLegacyTrail(bodyId: string, points: number[]): CachedTrailPath {
  const pointCount = Math.floor(points.length / 3)
  const cached = reusableTrailCache(bodyId, pointCount)
  const v = view.value
  const signature = trailCacheSignature(props.trailCutoffStep)
  if (cached.signature === signature) return cached
  const coordinates = cached.coordinates
  const projection = props.projection
  for (let index = 0; index < pointCount; index += 1) {
    const coordinateIndex = index * 3
    const x = points[coordinateIndex]
    const y = points[coordinateIndex + 1]
    const z = points[coordinateIndex + 2]
    const horizontal = projection === 'YZ' ? y : x
    const vertical = projection === 'XY' ? y : z
    const [px, py] = projectWorld(horizontal, vertical, v.scale)
    coordinates[index * 2] = px
    coordinates[index * 2 + 1] = py
  }
  cached.signature = signature
  return updateCachedTrailPath(cached, pointCount)
}

function rebuildTrailPathCache(): void {
  const activeBodyIds = new Set<string>()
  const cutoff = props.trailCutoffStep
  const cacheOne = (bodyId: string, points: BodyTrajectoryBuffer | number[]): void => {
    activeBodyIds.add(bodyId)
    trailPathCache.set(
      bodyId,
      points instanceof BodyTrajectoryBuffer
        ? projectRingTrail(bodyId, points, cutoff)
        : projectLegacyTrail(bodyId, points),
    )
  }
  if (props.trailsPerBody instanceof TrajectoryBuffer) props.trailsPerBody.forEachBody(cacheOne)
  else for (const [bodyId, points] of props.trailsPerBody) cacheOne(bodyId, points)
  for (const bodyId of trailPathCache.keys()) {
    if (!activeBodyIds.has(bodyId)) trailPathCache.delete(bodyId)
  }
}

function strokeCachedTrail(ctx: CanvasRenderingContext2D, cached: CachedTrailPath): void {
  if (cached.retainedIndices.length === 0) return
  if (cached.path) {
    ctx.stroke(cached.path)
    return
  }
  const first = cached.retainedIndices[0]
  ctx.beginPath()
  ctx.moveTo(cached.coordinates[first * 2], cached.coordinates[first * 2 + 1])
  for (let i = 1; i < cached.retainedIndices.length; i += 1) {
    const index = cached.retainedIndices[i]
    ctx.lineTo(cached.coordinates[index * 2], cached.coordinates[index * 2 + 1])
  }
  ctx.stroke()
}

function drawBackgroundLayer(): void {
  const ctx = backgroundContext.value
  const canvas = backgroundCanvasRef.value
  if (!ctx || !canvas) return
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  ctx.fillStyle = palette().background
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  drawGrid(ctx)
  backgroundDirty = false
}

function drawTrailLayer(nowMs: number): void {
  const ctx = trailContext.value
  const canvas = trailCanvasRef.value
  if (!ctx || !canvas) return
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  if (props.showTrails) {
    rebuildTrailPathCache()
    const v = view.value
    ctx.save()
    ctx.translate(v.offsetX, v.offsetY)
    ctx.globalAlpha = 0.65
    ctx.lineWidth = 1.2 * dpr
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'
    for (const [bodyId, cached] of trailPathCache) {
      ctx.strokeStyle = props.bodyColors.get(bodyId) ?? '#ffffff'
      strokeCachedTrail(ctx, cached)
    }
    ctx.restore()
    ctx.globalAlpha = 1
  } else {
    trailPathCache.clear()
  }
  lastTrailDrawMs = nowMs
  trailDirty = false
}

function drawBodies(ctx: CanvasRenderingContext2D, state: SimulationState): void {
  for (const body of state.bodies) {
    const [sx, sy] = project(body.position.x, body.position.y, body.position.z)
    const color = props.bodyColors.get(body.id) ?? '#ffffff'
    const isPair = props.nearestPairIds?.includes(body.id)

    // Solid translucent halos avoid constructing a new radial gradient on each
    // interpolated frame; color and radius are stable per body/quality tier.
    ctx.globalAlpha = isPair ? 0.18 : 0.12
    ctx.fillStyle = isPair ? '#ef6a7a' : color
    ctx.beginPath()
    ctx.arc(sx, sy, (isPair ? 22 : 18) * dpr, 0, Math.PI * 2)
    ctx.fill()
    ctx.globalAlpha = 1

    ctx.fillStyle = color
    ctx.beginPath()
    ctx.arc(sx, sy, 4 * dpr, 0, Math.PI * 2)
    ctx.fill()

    if (props.showLabels) {
      const name = props.bodyNames.get(body.id) ?? body.id.slice(0, 8)
      ctx.fillStyle = 'rgba(223, 233, 245, 0.9)'
      ctx.font = `${11 * dpr}px var(--mono)`
      ctx.fillText(name, sx + 8 * dpr, sy - 6 * dpr)
    }
  }
}

/** 事件标记：LIVE 显示活动近遇与选中事件，REVIEW 显示游标附近事件与选中事件。 */
function drawEventMarkers(ctx: CanvasRenderingContext2D, state: SimulationState): void {
  const stateById = new Map(state.bodies.map((body) => [body.id, body]))
  const reviewing = props.trailCutoffStep !== Number.POSITIVE_INFINITY
  const sampleStride = Math.max(1, Math.abs(view.value.scale) > 0 ? 100 : 1)
  for (const event of props.events) {
    if (event.type !== 'NEAR_ENCOUNTER' && event.type !== 'DIAGNOSTIC') continue
    if (event.phase === 'FINAL' && !reviewing && event.eventId !== props.selectedEventId) continue
    if (reviewing && event.closestStep !== null && event.closestStep !== undefined) {
      const distance = Math.abs((event.closestStep ?? event.step) - props.trailCutoffStep)
      if (distance > sampleStride && event.eventId !== props.selectedEventId) continue
    }
    const selected = event.eventId === props.selectedEventId
    let mid: { x: number; y: number; z: number } | null = event.midpointPosition ?? null
    if (!mid && event.bodyIds && event.bodyIds.length >= 2) {
      const a = stateById.get(event.bodyIds[0])
      const b = stateById.get(event.bodyIds[1])
      if (a && b) {
        mid = {
          x: (a.position.x + b.position.x) / 2,
          y: (a.position.y + b.position.y) / 2,
          z: (a.position.z + b.position.z) / 2,
        }
      }
    }
    if (!mid) continue
    const [mx, my] = project(mid.x, mid.y, mid.z)
    const isDiagnostic = event.type === 'DIAGNOSTIC'
    ctx.save()
    ctx.globalAlpha = selected ? 1 : 0.55
    // 天体连接线。
    if (event.bodyIds && event.bodyIds.length >= 2) {
      const a = stateById.get(event.bodyIds[0])
      const b = stateById.get(event.bodyIds[1])
      if (a && b) {
        const [ax, ay] = project(a.position.x, a.position.y, a.position.z)
        const [bx, by] = project(b.position.x, b.position.y, b.position.z)
        ctx.strokeStyle = selected ? '#35c9a4' : '#ef6a7a'
        ctx.lineWidth = (selected ? 1.4 : 1) * dpr
        ctx.setLineDash([4 * dpr, 3 * dpr])
        ctx.beginPath()
        ctx.moveTo(ax, ay)
        ctx.lineTo(bx, by)
        ctx.stroke()
        ctx.setLineDash([])
      }
    }
    // 警告标记：三角 + 感叹号。
    const radius = (selected ? 9 : 7) * dpr
    const color = isDiagnostic ? '#9db8ff' : selected ? '#35c9a4' : '#ef6a7a'
    ctx.fillStyle = color
    ctx.strokeStyle = '#0b1220'
    ctx.lineWidth = 1.2 * dpr
    ctx.beginPath()
    ctx.moveTo(mx, my - radius)
    ctx.lineTo(mx + radius * 0.866, my + radius * 0.5)
    ctx.lineTo(mx - radius * 0.866, my + radius * 0.5)
    ctx.closePath()
    ctx.fill()
    ctx.stroke()
    ctx.fillStyle = '#0b1220'
    ctx.font = `bold ${(selected ? 8 : 7) * dpr}px var(--mono)`
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText('!', mx, my + 0.5 * dpr)
    ctx.restore()
  }
}

function drawHud(ctx: CanvasRenderingContext2D, state: SimulationState, nowMs: number): void {
  const canvas = dynamicCanvasRef.value
  if (!canvas) return
  const hudKey = `${state.step}:${Math.floor(state.simulationTimeSeconds * 10)}:${view.value.scale}`
  if (hudKey !== cachedHudKey && nowMs - lastHudFormatMs >= 100) {
    cachedHudKey = hudKey
    lastHudFormatMs = nowMs
    cachedHudText = `step ${state.step.toLocaleString()}  t=${formatScientific(state.simulationTimeSeconds)} s  scale=${formatScientific(view.value.scale)} px/m`
  }
  ctx.fillStyle = palette().hudText
  ctx.font = `${10 * dpr}px var(--mono)`
  ctx.textBaseline = 'bottom'
  ctx.fillText(cachedHudText, 10 * dpr, canvas.height - 8 * dpr)

  if (!props.showPerformanceHud) return
  const stats = quality.stats
  const age = props.snapshotBuffer?.current
    ? Math.max(0, nowMs - props.snapshotBuffer.current.arrivalTimeMs)
    : null
  const actual = stats.actualFps === null ? '--' : stats.actualFps.toFixed(1)
  const p95 = stats.p95FrameMs === null ? '--' : stats.p95FrameMs.toFixed(2)
  const recent = stats.recentFrameMs === null ? '--' : stats.recentFrameMs.toFixed(2)
  const snapshotAge = age === null ? '--' : `${Math.round(age)}ms`
  ctx.textBaseline = 'top'
  ctx.fillStyle = palette().hudText
  ctx.fillText(
    `FPS ${actual}/${stats.targetFps.toFixed(0)} · draw p95 ${p95}ms recent ${recent}ms · snapshot ${snapshotAge} · DPR ${stats.effectiveDpr.toFixed(2)}`,
    10 * dpr,
    8 * dpr,
  )
}

/** 质量加权质心。 */
function centerOfMass(state: SimulationState): { x: number; y: number; z: number } | null {
  let totalMass = 0
  let cx = 0
  let cy = 0
  let cz = 0
  for (const body of state.bodies) {
    const mass = props.bodyMasses.get(body.id) ?? 1
    totalMass += mass
    cx += body.position.x * mass
    cy += body.position.y * mass
    cz += body.position.z * mass
  }
  if (totalMass <= 0) return null
  return { x: cx / totalMass, y: cy / totalMass, z: cz / totalMass }
}

/** 采样游标前可见轨迹点用于 AUTO_FIT 包围盒，限制单次遍历成本。 */
function collectBoundsPoints(): Array<{ x: number; y: number; z: number }> {
  const points: Array<{ x: number; y: number; z: number }> = []
  const cutoff = props.trailCutoffStep
  const pushTrail = (buffer: BodyTrajectoryBuffer): void => {
    const stride = Math.max(1, Math.floor(buffer.pointCount / 2000))
    let index = 0
    buffer.forEachCoordinates((step, x, y, z) => {
      if (step > cutoff) return
      if (index % stride === 0) points.push({ x, y, z })
      index += 1
    })
  }
  if (props.trailsPerBody instanceof TrajectoryBuffer) {
    props.trailsPerBody.forEachBody((_id, buffer) => pushTrail(buffer))
  } else {
    for (const [, value] of props.trailsPerBody) {
      if (value instanceof BodyTrajectoryBuffer) pushTrail(value)
    }
  }
  return points
}

/** AUTO_FIT：12 Hz 节流计算目标包围盒，20% 边距，迟滞 + 平滑。 */
function updateAutoFit(nowMs: number): void {
  const canvas = dynamicCanvasRef.value
  const state = displayState()
  if (!canvas || !state || state.bodies.length === 0) return
  if (nowMs - lastAutoFitMs < 83) return
  lastAutoFitMs = nowMs

  const boundsPoints = collectBoundsPoints()
  const candidates = [
    ...state.bodies.map((body) => body.position),
    ...boundsPoints,
  ]
  if (candidates.length === 0) return
  let minX = Number.POSITIVE_INFINITY
  let maxX = Number.NEGATIVE_INFINITY
  let minY = Number.POSITIVE_INFINITY
  let maxY = Number.NEGATIVE_INFINITY
  for (const p of candidates) {
    const horizontal = props.projection === 'YZ' ? p.y : p.x
    const vertical = props.projection === 'XY' ? p.y : p.z
    minX = Math.min(minX, horizontal); maxX = Math.max(maxX, horizontal)
    minY = Math.min(minY, vertical); maxY = Math.max(maxY, vertical)
  }
  const padding = 0.2
  const spanX = Math.max(1e-9, maxX - minX)
  const spanY = Math.max(1e-9, maxY - minY)
  const centerX = (minX + maxX) / 2
  const centerY = (minY + maxY) / 2
  const targetScale = Math.min(
    canvas.width / (spanX * (1 + padding)),
    canvas.height / (spanY * (1 + padding)),
  )
  // 迟滞：只有天体越出内侧 80% 区域或目标 scale 相差超过 10% 才更新目标。
  const v = view.value
  const innerScale = v.scale * 0.8
  const projectedCenterX = centerX * innerScale + v.offsetX
  const projectedCenterY = -centerY * innerScale + v.offsetY
  const escapesInner =
    projectedCenterX < canvas.width * 0.1 ||
    projectedCenterX > canvas.width * 0.9 ||
    projectedCenterY < canvas.height * 0.1 ||
    projectedCenterY > canvas.height * 0.9
  const scaleChanged = Math.abs(targetScale - autoFitLastScale) / Math.max(1e-30, autoFitLastScale) > 0.1
  if (escapesInner || scaleChanged) {
    const targetOffsetX = canvas.width / 2 - centerX * targetScale
    const targetOffsetY = canvas.height / 2 - centerY * targetScale
    autoFitTarget = { scale: targetScale, offsetX: targetOffsetX, offsetY: targetOffsetY }
    autoFitLastScale = targetScale
  }
}

/** 非 FREE 模式的跟随平移目标。 */
function followTarget(): { scale: number; offsetX: number; offsetY: number } | null {
  const canvas = dynamicCanvasRef.value
  const state = displayState()
  if (!canvas || !state) return null
  if (props.cameraMode === 'CENTER_OF_MASS') {
    const com = centerOfMass(state)
    if (!com) return null
    const horizontal = props.projection === 'YZ' ? com.y : com.x
    const vertical = props.projection === 'XY' ? com.y : com.z
    return {
      scale: view.value.scale,
      offsetX: canvas.width / 2 - horizontal * view.value.scale,
      offsetY: canvas.height / 2 - -vertical * view.value.scale,
    }
  }
  if (props.cameraMode === 'FOLLOW_BODY') {
    const body = state.bodies.find((item) => item.id === props.followBodyId)
    if (!body) return null
    const horizontal = props.projection === 'YZ' ? body.position.y : body.position.x
    const vertical = props.projection === 'XY' ? body.position.y : body.position.z
    return {
      scale: view.value.scale,
      offsetX: canvas.width / 2 - horizontal * view.value.scale,
      offsetY: canvas.height / 2 - -vertical * view.value.scale,
    }
  }
  if (props.cameraMode === 'AUTO_FIT') {
    return { ...autoFitTarget }
  }
  return null
}

function applyFollowSmoothing(): void {
  if (props.cameraMode === 'FREE') return
  const target = followTarget()
  if (!target) return
  const v = view.value
  const k = 0.15
  const next = {
    scale: v.scale + (target.scale - v.scale) * k,
    offsetX: v.offsetX + (target.offsetX - v.offsetX) * k,
    offsetY: v.offsetY + (target.offsetY - v.offsetY) * k,
  }
  const changed =
    Math.abs(next.scale - v.scale) > 1e-30 ||
    Math.abs(next.offsetX - v.offsetX) > 0.5 ||
    Math.abs(next.offsetY - v.offsetY) > 0.5
  if (changed) {
    view.value = next
    invalidateView()
  }
}

function drawDynamicLayer(nowMs: number): void {
  const ctx = dynamicContext.value
  const canvas = dynamicCanvasRef.value
  if (!ctx || !canvas) return
  const state = displayState(nowMs)
  if (!state) return
  if (props.cameraMode === 'AUTO_FIT') updateAutoFit(nowMs)
  applyFollowSmoothing()
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  drawBodies(ctx, state)
  drawEventMarkers(ctx, state)
  drawHud(ctx, state, nowMs)
  dynamicDirty = false
}

function renderFrame(timestamp: number): void {
  rafId = null
  if (!visible) return
  const started = displayNowMs()
  const nowMs = Number.isFinite(timestamp) && timestamp > 0 ? timestamp : started
  if (backgroundDirty) drawBackgroundLayer()
  const trailPolicy = trailRenderPolicy(quality.quality)
  const trailPeriodMs = 1000 / trailPolicy.maxFps
  if (trailDirty && nowMs - lastTrailDrawMs >= trailPeriodMs) drawTrailLayer(nowMs)
  if (dynamicDirty || shouldAnimate()) drawDynamicLayer(nowMs)
  const duration = Math.max(0, displayNowMs() - started)
  if (quality.recordFrame(duration, nowMs)) {
    // Quality changes alter DPR, RDP tolerance and trail redraw frequency.
    resize()
  }
  if (shouldAnimate(nowMs) || backgroundDirty || trailDirty || dynamicDirty) wake()
}

function onWheel(event: WheelEvent): void {
  event.preventDefault()
  const canvas = dynamicCanvasRef.value
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  const mouseX = event.clientX - rect.left
  const mouseY = event.clientY - rect.top
  const factor = event.deltaY > 0 ? 1 / 1.2 : 1.2
  const v = view.value
  const newScale = Math.min(1e-6, Math.max(1e-14, v.scale * factor))
  const worldX = (mouseX * dpr - v.offsetX) / v.scale
  const worldY = -(mouseY * dpr - v.offsetY) / v.scale
  view.value = {
    scale: newScale,
    offsetX: mouseX * dpr - worldX * newScale,
    offsetY: mouseY * dpr - worldY * newScale,
  }
  // 任意模式滚轮只修改 scale；切回 FREE 语义下用户主动缩放。
  invalidateView()
  hoverPointerClient = { x: event.clientX, y: event.clientY }
  refreshHover()
}

function clearHover(): void {
  hoverPointerClient = null
  if (!hoverInfo) return
  hoverInfo = null
  emit('hover-body', null)
}

function onPointerDown(event: PointerEvent): void {
  ;(event.target as HTMLElement).setPointerCapture(event.pointerId)
  dragging.value = {
    active: true,
    startX: event.clientX,
    startY: event.clientY,
    origOffsetX: view.value.offsetX,
    origOffsetY: view.value.offsetY,
  }
  clearHover()
  // 手动拖拽立即切回自由视角。
  if (props.cameraMode !== 'FREE') {
    emit('camera-mode-change', 'FREE')
  }
}

function onPointerMove(event: PointerEvent): void {
  if (dragging.value.active) {
    view.value = {
      ...view.value,
      offsetX: dragging.value.origOffsetX + (event.clientX - dragging.value.startX) * dpr,
      offsetY: dragging.value.origOffsetY + (event.clientY - dragging.value.startY) * dpr,
    }
    invalidateView()
    return
  }
  if (props.hoverEnabled) {
    updateHover(event)
  }
}

function updateHover(event: PointerEvent): void {
  const canvas = dynamicCanvasRef.value
  const state = displayState()
  if (!canvas || !state) {
    if (hoverInfo) {
      hoverInfo = null
      emit('hover-body', null)
    }
    return
  }
  hoverPointerClient = { x: event.clientX, y: event.clientY }
  refreshHover()
}

function refreshHover(): void {
  const canvas = dynamicCanvasRef.value
  const state = displayState()
  const pointer = hoverPointerClient
  if (!canvas || !state || !pointer || !props.hoverEnabled) {
    if (hoverInfo) {
      hoverInfo = null
      emit('hover-body', null)
    }
    return
  }
  const rect = canvas.getBoundingClientRect()
  const mouseX = (pointer.x - rect.left) * dpr
  const mouseY = (pointer.y - rect.top) * dpr
  const hitRadius = Math.max(10 * dpr, 4 * dpr + 4 * dpr)
  let best: HoverBodyInfo | null = null
  let bestDistance = Number.POSITIVE_INFINITY
  for (const body of state.bodies) {
    const [sx, sy] = project(body.position.x, body.position.y, body.position.z)
    const dx = sx - mouseX
    const dy = sy - mouseY
    const distance = Math.hypot(dx, dy)
    if (distance <= hitRadius && distance < bestDistance) {
      bestDistance = distance
      best = {
        bodyId: body.id,
        bodyState: body,
        anchorCssX: sx / dpr,
        anchorCssY: sy / dpr,
      }
    }
  }
  const changed =
    (best === null) !== (hoverInfo === null) ||
    (best !== null && hoverInfo !== null && (
      best.bodyId !== hoverInfo.bodyId ||
      best.bodyState !== hoverInfo.bodyState ||
      best.anchorCssX !== hoverInfo.anchorCssX ||
      best.anchorCssY !== hoverInfo.anchorCssY
    ))
  if (changed) {
    hoverInfo = best
    emit('hover-body', best)
  }
}

function onPointerUp(event: PointerEvent): void {
  dragging.value.active = false
  ;(event.target as HTMLElement).releasePointerCapture?.(event.pointerId)
  if (props.hoverEnabled) updateHover(event)
}

function onPointerLeave(event: PointerEvent): void {
  if (dragging.value.active) onPointerUp(event)
  clearHover()
}

function onDoubleClick(): void {
  fitToContent()
}

function onVisibilityChange(): void {
  visible = typeof document === 'undefined' || !document.hidden
  if (!visible) {
    if (rafId !== null) cancelAnimationFrame(rafId)
    rafId = null
    return
  }
  backgroundDirty = true
  trailDirty = true
  dynamicDirty = true
  resize()
  wake()
}

onMounted(() => {
  const backgroundCanvas = backgroundCanvasRef.value
  const trailCanvas = trailCanvasRef.value
  const dynamicCanvas = dynamicCanvasRef.value
  if (!backgroundCanvas || !trailCanvas || !dynamicCanvas) return
  backgroundContext.value = backgroundCanvas.getContext('2d')
  trailContext.value = trailCanvas.getContext('2d')
  dynamicContext.value = dynamicCanvas.getContext('2d')
  resize()
  if (wrapRef.value) {
    resizeObserver = new ResizeObserver(resize)
    resizeObserver.observe(wrapRef.value)
  }
  window.addEventListener('resize', resize)
  document.addEventListener('visibilitychange', onVisibilityChange)
  fitToContent()
  // Draw synchronously once for accessibility/tests, then hand off to rAF.
  renderFrame(displayNowMs())
  wake()
})

onBeforeUnmount(() => {
  if (rafId !== null) cancelAnimationFrame(rafId)
  if (resizeObserver) resizeObserver.disconnect()
  window.removeEventListener('resize', resize)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  if (hoverInfo) emit('hover-body', null)
})

watch(
  () => props.state?.step ?? -1,
  (step, prev) => {
    if (prev === -1 && step >= 0) fitToContent()
    invalidateDynamic()
  },
)

watch(() => props.trailVersion, invalidateTrails)
watch(() => props.projection, () => {
  fitToContent()
  invalidateView()
})
watch(() => props.showTrails, invalidateTrails)
watch(() => props.showGrid, invalidateBackground)
watch(() => props.showLabels, invalidateDynamic)
watch(() => props.showPerformanceHud, invalidateDynamic)
watch(() => props.bodyColors, () => {
  invalidateTrails()
  invalidateDynamic()
})
watch(() => props.palette, () => {
  backgroundDirty = true
  trailDirty = true
  dynamicDirty = true
  wake()
})
watch(
  () => props.trailCutoffStep,
  () => {
    // 游标变化使可见轨迹范围变化；scale 未变时路径已按 cutoff 重建。
    trailDirty = true
    wake()
  },
)
watch(
  () => [props.cameraMode, props.followBodyId],
  () => {
    if (props.cameraMode === 'FOLLOW_BODY' && props.followBodyId) {
      // 切换到跟随目标时立即对齐，避免从远处滑入。
      const target = followTarget()
      if (target) view.value = { ...target }
    }
    invalidateDynamic()
  },
)
watch(
  () => props.events,
  () => invalidateDynamic(),
)
watch(
  () => props.hoverEnabled,
  (enabled) => {
    if (!enabled) clearHover()
  },
)

defineExpose({ fitToContent })
</script>

<template>
  <div ref="wrapRef" class="simulation-canvas-wrap">
    <canvas
      ref="dynamicCanvasRef"
      class="simulation-canvas simulation-canvas-dynamic"
      :aria-label="`模拟视图，${projectionLabel}`"
      @wheel="onWheel"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
      @pointerleave="onPointerLeave"
      @dblclick="onDoubleClick"
    ></canvas>
    <canvas
      ref="trailCanvasRef"
      class="simulation-canvas simulation-canvas-trail"
      aria-hidden="true"
    ></canvas>
    <canvas
      ref="backgroundCanvasRef"
      class="simulation-canvas simulation-canvas-background"
      aria-hidden="true"
    ></canvas>
    <div class="canvas-hint">滚轮缩放 · 拖拽平移 · 双击适应窗口</div>
  </div>
</template>
