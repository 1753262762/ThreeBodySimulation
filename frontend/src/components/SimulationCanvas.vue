<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import type { SimulationState } from '../contracts'
import type { ProjectionPlane } from '../stores/preferences'
import { formatScientific } from '../lib/format'

/**
 * Canvas 三投影渲染器。
 *
 * 支持 XY / XZ / YZ 三种正交投影，所有天体共用一套三维轨迹数据，
 * 切换投影只改变屏幕映射方式。
 * 渲染循环与 WebSocket 接收解耦：每个 rAF 帧绘制最新快照，
 * 保证 30 Hz 以下的输入也能得到 60 FPS 的平滑体验。
 */
const props = defineProps<{
  state: SimulationState | null
  trailsPerBody: Map<string, number[]>
  trailVersion: number
  projection: ProjectionPlane
  showTrails: boolean
  showLabels: boolean
  showGrid: boolean
  bodyNames: Map<string, string>
  bodyColors: Map<string, string>
  nearestPairIds: string[] | null
}>()

const canvasRef = ref<HTMLCanvasElement | null>(null)
const wrapRef = ref<HTMLDivElement | null>(null)

// 视图变换：scale 为每米像素数（通常 1e-10 量级），offset 为屏幕中心偏移像素
const view = shallowRef({ scale: 1e-10, offsetX: 0, offsetY: 0 })
const dragging = shallowRef({ active: false, startX: 0, startY: 0, origOffsetX: 0, origOffsetY: 0 })
let rafId: number | null = null
let dpr = 1

const projectionLabel = computed(() => `${props.projection} 投影视图`)

function resize(): void {
  const canvas = canvasRef.value
  const wrap = wrapRef.value
  if (!canvas || !wrap) return
  dpr = window.devicePixelRatio || 1
  const rect = wrap.getBoundingClientRect()
  canvas.width = Math.max(1, Math.floor(rect.width * dpr))
  canvas.height = Math.max(1, Math.floor(rect.height * dpr))
  canvas.style.width = `${rect.width}px`
  canvas.style.height = `${rect.height}px`
}

function project(x: number, y: number, z: number): [number, number] {
  const v = view.value
  switch (props.projection) {
    case 'XY':
      return [x * v.scale + v.offsetX, -y * v.scale + v.offsetY]
    case 'XZ':
      return [x * v.scale + v.offsetX, -z * v.scale + v.offsetY]
    case 'YZ':
      return [y * v.scale + v.offsetX, -z * v.scale + v.offsetY]
  }
}


function fitToContent(): void {
  const state = props.state
  if (!state || state.bodies.length === 0) {
    view.value = { scale: 1e-10, offsetX: 0, offsetY: 0 }
    return
  }
  let minX = Number.POSITIVE_INFINITY, maxX = Number.NEGATIVE_INFINITY
  let minY = Number.POSITIVE_INFINITY, maxY = Number.NEGATIVE_INFINITY
  for (const body of state.bodies) {
    const [sx, sy] = projectPoint(body.position.x, body.position.y, body.position.z)
    minX = Math.min(minX, sx); maxX = Math.max(maxX, sx)
    minY = Math.min(minY, sy); maxY = Math.max(maxY, sy)
  }
  // 先以单位 scale 算出空间范围，再倒推合适 scale
  const padding = 0.2
  const canvas = canvasRef.value!
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
}

function projectPoint(x: number, y: number, z: number): [number, number] {
  switch (props.projection) {
    case 'XY': return [x, y]
    case 'XZ': return [x, z]
    case 'YZ': return [y, z]
  }
}

function drawGrid(ctx: CanvasRenderingContext2D): void {
  if (!props.showGrid) return
  const canvas = canvasRef.value!
  const v = view.value
  const cx = canvas.width / 2
  const cy = canvas.height / 2
  ctx.strokeStyle = 'rgba(143, 163, 189, 0.08)'
  ctx.lineWidth = 1

  // 自适应网格间距：像素间距 ~60px
  const targetPixels = 60 * dpr
  const worldSpacing = targetPixels / (v.scale * dpr)
  const magnitude = 10 ** Math.floor(Math.log10(worldSpacing))
  const factor = worldSpacing / magnitude
  let step = magnitude
  if (factor > 5) step = magnitude * 10
  else if (factor > 2) step = magnitude * 5
  else if (factor > 1) step = magnitude * 2

  const leftWorld = -cx / (v.scale * dpr) - v.offsetX / (v.scale)
  const rightWorld = cx / (v.scale * dpr) - v.offsetX / (v.scale)
  const topWorld = -cy / (v.scale * dpr) - v.offsetY / (v.scale)
  const bottomWorld = cy / (v.scale * dpr) - v.offsetY / (v.scale)

  ctx.beginPath()
  for (let x = Math.floor(leftWorld / step) * step; x <= rightWorld; x += step) {
    const px = cx + x * v.scale * dpr + v.offsetX * dpr
    ctx.moveTo(px, 0); ctx.lineTo(px, canvas.height)
  }
  for (let y = Math.floor(topWorld / step) * step; y <= bottomWorld; y += step) {
    const py = cy + y * v.scale * dpr + v.offsetY * dpr
    ctx.moveTo(0, py); ctx.lineTo(canvas.width, py)
  }
  ctx.stroke()

  // 坐标轴
  ctx.strokeStyle = 'rgba(143, 163, 189, 0.35)'
  ctx.lineWidth = 1
  ctx.beginPath()
  const xAxisY = cy + 0 * v.scale * dpr + v.offsetY * dpr
  ctx.moveTo(0, xAxisY); ctx.lineTo(canvas.width, xAxisY)
  const yAxisX = cx + 0 * v.scale * dpr + v.offsetX * dpr
  ctx.moveTo(yAxisX, 0); ctx.lineTo(yAxisX, canvas.height)
  ctx.stroke()
}

function drawTrails(ctx: CanvasRenderingContext2D): void {
  if (!props.showTrails) return
  for (const [bodyId, points] of props.trailsPerBody) {
    const color = props.bodyColors.get(bodyId) ?? '#ffffff'
    ctx.strokeStyle = color
    ctx.globalAlpha = 0.65
    ctx.lineWidth = 1.2 * dpr
    ctx.beginPath()
    for (let i = 0; i < points.length; i += 3) {
      const [sx, sy] = project(points[i], points[i + 1], points[i + 2])
      if (i === 0) ctx.moveTo(sx, sy)
      else ctx.lineTo(sx, sy)
    }
    ctx.stroke()
  }
  ctx.globalAlpha = 1
}

function drawBodies(ctx: CanvasRenderingContext2D): void {
  const state = props.state
  if (!state) return
  for (const body of state.bodies) {
    const [sx, sy] = project(body.position.x, body.position.y, body.position.z)
    const color = props.bodyColors.get(body.id) ?? '#ffffff'
    const isPair = props.nearestPairIds?.includes(body.id)

    // glow
    if (isPair) {
      const gradient = ctx.createRadialGradient(sx, sy, 0, sx, sy, 22 * dpr)
      gradient.addColorStop(0, 'rgba(239, 106, 122, 0.6)')
      gradient.addColorStop(1, 'rgba(239, 106, 122, 0)')
      ctx.fillStyle = gradient
      ctx.beginPath()
      ctx.arc(sx, sy, 22 * dpr, 0, Math.PI * 2)
      ctx.fill()
    } else {
      const gradient = ctx.createRadialGradient(sx, sy, 0, sx, sy, 18 * dpr)
      gradient.addColorStop(0, color)
      gradient.addColorStop(1, 'transparent')
      ctx.globalAlpha = 0.35
      ctx.fillStyle = gradient
      ctx.beginPath()
      ctx.arc(sx, sy, 18 * dpr, 0, Math.PI * 2)
      ctx.fill()
      ctx.globalAlpha = 1
    }

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

function drawHud(ctx: CanvasRenderingContext2D): void {
  const state = props.state
  if (!state) return
  const canvas = canvasRef.value!
  ctx.fillStyle = 'rgba(141, 149, 162, 0.75)'
  ctx.font = `${10 * dpr}px var(--mono)`
  ctx.textBaseline = 'bottom'
  ctx.fillText(
    `step ${state.step.toLocaleString()}  t=${formatScientific(state.simulationTimeSeconds)} s  scale=${formatScientific(view.value.scale)} px/m`,
    10 * dpr,
    canvas.height - 8 * dpr,
  )
}

function render(): void {
  const canvas = canvasRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.clearRect(0, 0, canvas.width, canvas.height)

  // 背景
  ctx.fillStyle = '#050a13'
  ctx.fillRect(0, 0, canvas.width, canvas.height)

  drawGrid(ctx)
  drawTrails(ctx)
  drawBodies(ctx)
  drawHud(ctx)

  rafId = requestAnimationFrame(render)
}

function onWheel(event: WheelEvent): void {
  event.preventDefault()
  const canvas = canvasRef.value!
  const rect = canvas.getBoundingClientRect()
  const mouseX = event.clientX - rect.left
  const mouseY = event.clientY - rect.top
  const factor = event.deltaY > 0 ? 1 / 1.2 : 1.2
  const v = view.value
  const newScale = Math.min(1e-6, Math.max(1e-14, v.scale * factor))
  // 以鼠标位置为中心缩放（全部使用物理像素）
  const worldX = (mouseX * dpr - v.offsetX) / v.scale
  const worldY = -(mouseY * dpr - v.offsetY) / v.scale
  view.value = {
    scale: newScale,
    offsetX: mouseX * dpr - worldX * newScale,
    offsetY: mouseY * dpr - worldY * newScale,
  }
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
}

function onPointerUp(event: PointerEvent): void {
  dragging.value.active = false
  ;(event.target as HTMLElement).releasePointerCapture?.(event.pointerId)
}

function onDoubleClick(): void {
  fitToContent()
}

let resizeObserver: ResizeObserver | null = null

onMounted(() => {
  resize()
  if (wrapRef.value) {
    resizeObserver = new ResizeObserver(() => resize())
    resizeObserver.observe(wrapRef.value)
  }
  window.addEventListener('resize', resize)
  fitToContent()
  render()
})

onBeforeUnmount(() => {
  if (rafId !== null) cancelAnimationFrame(rafId)
  if (resizeObserver) resizeObserver.disconnect()
  window.removeEventListener('resize', resize)
})

watch(
  () => props.state?.step ?? -1,
  (step, prev) => {
    // 首次拿到实时快照时自动适应窗口，避免使用默认缩放。
    if (prev === -1 && step >= 0) {
      fitToContent()
    }
  },
)

watch(
  () => props.projection,
  () => fitToContent(),
)

defineExpose({ fitToContent })
</script>

<template>
  <div ref="wrapRef" class="simulation-canvas-wrap">
    <canvas
      ref="canvasRef"
      class="simulation-canvas"
      :aria-label="`模拟视图，${projectionLabel}`"
      @wheel.passive="onWheel"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
      @dblclick="onDoubleClick"
    ></canvas>
    <div class="canvas-hint">滚轮缩放 · 拖拽平移 · 双击适应窗口</div>
  </div>
</template>
