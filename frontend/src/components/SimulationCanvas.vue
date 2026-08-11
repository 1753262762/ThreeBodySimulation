<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import type { SimulationState } from '../contracts'
import type { ProjectionPlane } from '../stores/preferences'
import { formatScientific } from '../lib/format'
import { SnapshotBuffer } from '../lib/snapshotBuffer'
import { BodyTrajectoryBuffer, TrajectoryBuffer } from '../lib/trajectoryBuffer'
import { AdaptiveQualityController } from '../lib/renderQuality'
import { projectGridPoint, visibleGridBounds } from '../lib/gridProjection'
import { simplifyFlatPolylineIndices, trailRenderPolicy } from '../lib/trailSampling'

type LegacyTrailCollection = Map<string, number[] | BodyTrajectoryBuffer>
type TrailCollection = TrajectoryBuffer | LegacyTrailCollection

/**
 * Canvas 三投影渲染器。
 *
 * 背景、轨迹和动态天体使用三个独立 Canvas，共享同一套尺寸与视图
 * 变换。轨迹层按自适应频率重建真实点 Path2D，天体层保持高刷新插值。
 */
const props = defineProps<{
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
  nearestPairIds: string[] | null
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

interface CachedTrailPath {
  coordinates: Float64Array
  pointCount: number
  retainedIndices: number[]
  path: Path2D | null
}

const trailPathCache = new Map<string, CachedTrailPath>()

let cachedHudText = ''
let cachedHudKey = ''
let lastHudFormatMs = Number.NEGATIVE_INFINITY

function displayNowMs(): number {
  if (typeof performance !== 'undefined' && Number.isFinite(performance.now())) return performance.now()
  return Date.now()
}

function displayState(atTimeMs = displayNowMs()): SimulationState | null {
  return props.snapshotBuffer?.readInterpolated(atTimeMs) ?? props.state
}

const projectionLabel = computed(() => `${props.projection} 投影视图`)

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
  quality.setDevicePixelRatio(typeof window === 'undefined' ? 1 : window.devicePixelRatio)
  dpr = quality.effectiveDpr
  const rect = wrap.getBoundingClientRect()
  const width = Math.max(1, Math.floor(rect.width * dpr))
  const height = Math.max(1, Math.floor(rect.height * dpr))
  for (const canvas of [backgroundCanvas, trailCanvas, dynamicCanvas]) {
    canvas.width = width
    canvas.height = height
    canvas.style.width = `${rect.width}px`
    canvas.style.height = `${rect.height}px`
  }
  invalidateView()
}

function project(x: number, y: number, z: number): [number, number] {
  const v = view.value
  switch (props.projection) {
    case 'XY': return [x * v.scale + v.offsetX, -y * v.scale + v.offsetY]
    case 'XZ': return [x * v.scale + v.offsetX, -z * v.scale + v.offsetY]
    case 'YZ': return [y * v.scale + v.offsetX, -z * v.scale + v.offsetY]
  }
}

function projectPoint(x: number, y: number, z: number): [number, number] {
  switch (props.projection) {
    case 'XY': return [x, y]
    case 'XZ': return [x, z]
    case 'YZ': return [y, z]
  }
}

function fitToContent(): void {
  const state = displayState()
  const canvas = dynamicCanvasRef.value
  if (!canvas || !state || state.bodies.length === 0) {
    view.value = { scale: 1e-10, offsetX: 0, offsetY: 0 }
    invalidateView()
    return
  }
  let minX = Number.POSITIVE_INFINITY, maxX = Number.NEGATIVE_INFINITY
  let minY = Number.POSITIVE_INFINITY, maxY = Number.NEGATIVE_INFINITY
  for (const body of state.bodies) {
    const [sx, sy] = projectPoint(body.position.x, body.position.y, body.position.z)
    minX = Math.min(minX, sx); maxX = Math.max(maxX, sx)
    minY = Math.min(minY, sy); maxY = Math.max(maxY, sy)
  }
  const padding = 0.2
  const spanX = Math.max(1e-9, maxX - minX)
  const spanY = Math.max(1e-9, maxY - minY)
  const centerX = (minX + maxX) / 2
  const centerY = (minY + maxY) / 2
  const scale = Math.min(canvas.width / (spanX * (1 + padding)), canvas.height / (spanY * (1 + padding)))
  view.value = {
    scale,
    offsetX: canvas.width / 2 - centerX * scale,
    offsetY: canvas.height / 2 - centerY * scale,
  }
  invalidateView()
}

function drawGrid(ctx: CanvasRenderingContext2D): void {
  if (!props.showGrid) return
  const canvas = backgroundCanvasRef.value
  if (!canvas) return
  const v = view.value
  ctx.strokeStyle = 'rgba(143, 163, 189, 0.08)'
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

  ctx.strokeStyle = 'rgba(143, 163, 189, 0.35)'
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
  }
}

function projectRingTrail(bodyId: string, points: BodyTrajectoryBuffer): CachedTrailPath {
  const cached = reusableTrailCache(bodyId, points.capacity)
  const coordinates = cached.coordinates
  const v = view.value
  const projection = props.projection
  points.forEachCoordinates((_step, x, y, z, index) => {
    const horizontal = projection === 'YZ' ? y : x
    const vertical = projection === 'XY' ? y : z
    coordinates[index * 2] = horizontal * v.scale + v.offsetX
    coordinates[index * 2 + 1] = -vertical * v.scale + v.offsetY
  })
  return updateCachedTrailPath(cached, points.pointCount)
}

function projectLegacyTrail(bodyId: string, points: number[]): CachedTrailPath {
  const pointCount = Math.floor(points.length / 3)
  const cached = reusableTrailCache(bodyId, pointCount)
  const coordinates = cached.coordinates
  const v = view.value
  const projection = props.projection
  for (let index = 0; index < pointCount; index += 1) {
    const coordinateIndex = index * 3
    const x = points[coordinateIndex]
    const y = points[coordinateIndex + 1]
    const z = points[coordinateIndex + 2]
    const horizontal = projection === 'YZ' ? y : x
    const vertical = projection === 'XY' ? y : z
    coordinates[index * 2] = horizontal * v.scale + v.offsetX
    coordinates[index * 2 + 1] = -vertical * v.scale + v.offsetY
  }
  return updateCachedTrailPath(cached, pointCount)
}

function rebuildTrailPathCache(): void {
  const activeBodyIds = new Set<string>()
  const cacheOne = (bodyId: string, points: BodyTrajectoryBuffer | number[]): void => {
    activeBodyIds.add(bodyId)
    trailPathCache.set(
      bodyId,
      points instanceof BodyTrajectoryBuffer
        ? projectRingTrail(bodyId, points)
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
  ctx.fillStyle = '#050a13'
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
    ctx.globalAlpha = 0.65
    ctx.lineWidth = 1.2 * dpr
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'
    for (const [bodyId, cached] of trailPathCache) {
      ctx.strokeStyle = props.bodyColors.get(bodyId) ?? '#ffffff'
      strokeCachedTrail(ctx, cached)
    }
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

function drawHud(ctx: CanvasRenderingContext2D, state: SimulationState, nowMs: number): void {
  const canvas = dynamicCanvasRef.value
  if (!canvas) return
  const hudKey = `${state.step}:${Math.floor(state.simulationTimeSeconds * 10)}:${view.value.scale}`
  if (hudKey !== cachedHudKey && nowMs - lastHudFormatMs >= 100) {
    cachedHudKey = hudKey
    lastHudFormatMs = nowMs
    cachedHudText = `step ${state.step.toLocaleString()}  t=${formatScientific(state.simulationTimeSeconds)} s  scale=${formatScientific(view.value.scale)} px/m`
  }
  ctx.fillStyle = 'rgba(141, 149, 162, 0.75)'
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
  ctx.fillStyle = 'rgba(141, 149, 162, 0.9)'
  ctx.fillText(
    `FPS ${actual}/${stats.targetFps.toFixed(0)} · draw p95 ${p95}ms recent ${recent}ms · snapshot ${snapshotAge} · DPR ${stats.effectiveDpr.toFixed(2)}`,
    10 * dpr,
    8 * dpr,
  )
}

function drawDynamicLayer(nowMs: number): void {
  const ctx = dynamicContext.value
  const canvas = dynamicCanvasRef.value
  const state = displayState(nowMs)
  if (!ctx || !canvas) return
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  if (state) {
    drawBodies(ctx, state)
    drawHud(ctx, state, nowMs)
  }
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
  invalidateView()
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
}

function onPointerMove(event: PointerEvent): void {
  if (!dragging.value.active) return
  view.value = {
    ...view.value,
    offsetX: dragging.value.origOffsetX + (event.clientX - dragging.value.startX) * dpr,
    offsetY: dragging.value.origOffsetY + (event.clientY - dragging.value.startY) * dpr,
  }
  invalidateView()
}

function onPointerUp(event: PointerEvent): void {
  dragging.value.active = false
  ;(event.target as HTMLElement).releasePointerCapture?.(event.pointerId)
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
