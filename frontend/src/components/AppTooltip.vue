<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'

/**
 * 无依赖统一 Tooltip。
 *
 * hover 与键盘 focus 均可打开；Escape、blur、pointerleave 关闭。
 * 内容节点 role="tooltip"，触发节点通过 aria-describedby 关联。
 * Teleport 到 body 并使用 fixed 定位，避免被参数侧栏 overflow 裁剪；
 * 视口边缘保留 8 px，上下空间不足时翻转。disabled 按钮可用
 * focusable 包装获得键盘可聚焦的触发器。
 */
withDefaults(
  defineProps<{
    /** 简单文案；需要富内容时使用 content 插槽。 */
    text?: string
    /** 可选的加粗标题。 */
    title?: string
    /** disabled 触发器需要可聚焦包装时开启。 */
    focusable?: boolean
  }>(),
  {
    text: undefined,
    title: undefined,
    focusable: false,
  },
)

const VIEWPORT_MARGIN = 8
const VERTICAL_GAP = 10

const wrapRef = ref<HTMLElement | null>(null)
const tipRef = ref<HTMLElement | null>(null)
const open = ref(false)
const position = ref<{ left: number; top: number }>({ left: 0, top: 0 })
const pointerAnchor = ref<{ x: number; y: number } | null>(null)

const contentId = `app-tooltip-${Math.random().toString(36).slice(2, 10)}`

let positionRaf: number | null = null

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value))
}

function computePosition(): void {
  const wrap = wrapRef.value
  const tip = tipRef.value
  if (!wrap || !tip) return
  const wrapRect = wrap.getBoundingClientRect()
  const tipRect = tip.getBoundingClientRect()
  const tipWidth = tipRect.width || 220
  const tipHeight = tipRect.height || 48
  const viewportWidth = window.innerWidth
  const viewportHeight = window.innerHeight
  const anchorX = pointerAnchor.value?.x ?? wrapRect.left + wrapRect.width / 2
  const anchorY = pointerAnchor.value?.y ?? wrapRect.top + wrapRect.height / 2

  const spaceBelow = viewportHeight - anchorY
  const spaceAbove = anchorY
  const placeAbove = spaceBelow < tipHeight + VERTICAL_GAP && spaceAbove > spaceBelow

  let left = anchorX
  // 水平方向超出右缘时向左翻转，随后夹紧到视口内。
  if (left + tipWidth > viewportWidth - VIEWPORT_MARGIN) {
    left = anchorX - tipWidth
  }
  left = clamp(left, VIEWPORT_MARGIN, viewportWidth - tipWidth - VIEWPORT_MARGIN)
  const top = placeAbove ? anchorY - tipHeight - VERTICAL_GAP : anchorY + VERTICAL_GAP

  position.value = { left, top }
}

function schedulePosition(): void {
  if (positionRaf !== null || typeof requestAnimationFrame !== 'function') return
  positionRaf = requestAnimationFrame(() => {
    positionRaf = null
    computePosition()
  })
}

function show(anchor?: { x: number; y: number }): void {
  if (anchor) pointerAnchor.value = anchor
  open.value = true
  void nextTick(schedulePosition)
}

function hide(): void {
  open.value = false
  pointerAnchor.value = null
}

function onPointerEnter(event: PointerEvent): void {
  show({ x: event.clientX, y: event.clientY })
}

function onPointerMove(event: PointerEvent): void {
  if (!open.value) return
  pointerAnchor.value = { x: event.clientX, y: event.clientY }
  schedulePosition()
}

function onFocusIn(): void {
  show()
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') hide()
}

const triggerAria = computed(() => (open.value ? contentId : undefined))

onBeforeUnmount(() => {
  if (positionRaf !== null) {
    cancelAnimationFrame(positionRaf)
    positionRaf = null
  }
})
</script>

<template>
  <span
    ref="wrapRef"
    class="tooltip-wrap"
    :tabindex="focusable ? 0 : undefined"
    :aria-describedby="triggerAria"
    @pointerenter="onPointerEnter"
    @pointermove="onPointerMove"
    @pointerleave="hide"
    @focusin="onFocusIn"
    @focusout="hide"
    @keydown="onKeydown"
  >
    <slot />
  </span>
  <Teleport to="body">
    <div
      v-if="open"
      ref="tipRef"
      :id="contentId"
      role="tooltip"
      class="app-tooltip"
      :style="{ left: position.left + 'px', top: position.top + 'px' }"
    >
      <span v-if="title" class="tooltip-title">{{ title }}</span>
      <p v-if="text">{{ text }}</p>
      <slot name="content" />
    </div>
  </Teleport>
</template>
