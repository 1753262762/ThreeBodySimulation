<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useExperimentsStore } from '../stores/experiments'
import { usePreferencesStore } from '../stores/preferences'
import { useLongPress } from '../lib/useLongPress'
import { formatSimulationTime, formatInteger, formatScientific } from '../lib/format'
import { fromSi, type UnitSystem } from '../lib/units'
import type { SimulationEvent } from '../contracts'
import AppTooltip from './AppTooltip.vue'

const experimentsStore = useExperimentsStore()
const preferences = usePreferencesStore()

const trackRef = ref<HTMLElement | null>(null)
const scrubbing = ref(false)

const PLAYBACK_MODE_LABELS: Record<string, string> = {
  LIVE: '实时',
  REVIEW_PAUSED: '回看中',
  REVIEW_PLAYING: '回放中',
}
const PRECISION_LABELS: Record<string, string> = {
  EXACT: '精确',
  APPROXIMATE: '近似',
  RESOLVING: '解析中',
  ERROR: '错误',
}

const currentStep = computed(() => experimentsStore.liveState?.step ?? experimentsStore.current?.progress.step ?? 0)
const cursorStep = computed(() => experimentsStore.cursorStep)
const totalRange = computed(() => Math.max(1, experimentsStore.plannedEndStep))
const upper = computed(() => experimentsStore.availableToStep ?? currentStep.value)
const lower = computed(() => experimentsStore.availableFromStep ?? 0)

const isReviewing = computed(() => experimentsStore.isReviewing)
const runningWhileReview = computed(
  () => isReviewing.value && experimentsStore.current?.status === 'RUNNING',
)

/** 当前权威步在时间轴上的百分比位置。 */
function ratioOf(step: number): number {
  return Math.min(1, Math.max(0, step / totalRange.value))
}

const currentRatio = computed(() => ratioOf(currentStep.value))
const cursorRatio = computed(() => ratioOf(cursorStep.value))
const archivedLeft = computed(() => ratioOf(lower.value))
const archivedRight = computed(() => ratioOf(upper.value))

/** 事件刻度：聚合同一步的 Close Encounter 与 DIAGNOSTIC。 */
interface EventTick {
  step: number
  events: SimulationEvent[]
  tooltip: string
}
const eventTicks = computed<EventTick[]>(() => {
  const byStep = new Map<number, SimulationEvent[]>()
  for (const event of experimentsStore.events) {
    if (event.type !== 'NEAR_ENCOUNTER' && event.type !== 'DIAGNOSTIC') continue
    const step = event.closestStep ?? event.step
    const list = byStep.get(step)
    if (list) list.push(event)
    else byStep.set(step, [event])
  }
  return [...byStep.entries()]
    .map(([step, events]) => ({
      step,
      events,
      tooltip: events
        .map((event) => {
          const type = event.type === 'DIAGNOSTIC' ? '诊断' : '近距离'
          const phase = event.phase ? `·${event.phase}` : ''
          const distance = event.closestDistanceMeters ?? event.distanceMeters
          const distanceText =
            distance !== null && distance !== undefined
              ? `，最近 ${formatScientific(fromSi(distance, 'length', unitSystem()))} ${unitSystem() === 'ASTRONOMICAL' ? 'AU' : 'm'}`
              : ''
          return `${type}${phase} @步 ${formatInteger(step)}${distanceText}`
        })
        .join('；'),
    }))
    .sort((a, b) => a.step - b.step)
})

function unitSystem(): UnitSystem {
  return preferences.unitSystem
}

function stepFromEvent(event: PointerEvent): number {
  const track = trackRef.value
  if (!track) return cursorStep.value
  const rect = track.getBoundingClientRect()
  if (rect.width <= 0) return cursorStep.value
  const ratio = (event.clientX - rect.left) / rect.width
  return Math.max(0, Math.round(ratio * upper.value))
}

let moveRafId: number | null = null
let pendingMoveStep: number | null = null

function onPointerDown(event: PointerEvent): void {
  if (event.button !== 0) return
  scrubbing.value = true
  ;(event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId)
  const step = stepFromEvent(event)
  experimentsStore.beginReviewScrub(step)
}

function onPointerMove(event: PointerEvent): void {
  if (!scrubbing.value) return
  pendingMoveStep = stepFromEvent(event)
  if (moveRafId !== null || typeof requestAnimationFrame !== 'function') return
  moveRafId = requestAnimationFrame(() => {
    moveRafId = null
    if (pendingMoveStep !== null) {
      experimentsStore.seekCursorFromCache(pendingMoveStep)
      pendingMoveStep = null
    }
  })
}

function onPointerUp(event: PointerEvent): void {
  if (!scrubbing.value) return
  scrubbing.value = false
  ;(event.currentTarget as HTMLElement).releasePointerCapture?.(event.pointerId)
  if (pendingMoveStep !== null && moveRafId !== null) {
    // 立即应用最后一次移动，避免 pointerup 时游标滞后。
    experimentsStore.seekCursorFromCache(pendingMoveStep)
  }
  if (moveRafId !== null && typeof cancelAnimationFrame === 'function') {
    cancelAnimationFrame(moveRafId)
    moveRafId = null
  }
  pendingMoveStep = null
  if (experimentsStore.current?.id) {
    void experimentsStore.enterReview(experimentsStore.current.id, experimentsStore.cursorStep)
  }
}

function onEventTick(tick: EventTick): void {
  const id = experimentsStore.current?.id
  if (!id) return
  const event = tick.events[0]
  if (event) void experimentsStore.locateEvent(id, event)
}

const jumpBack = useLongPress({
  onTap: () => experimentsStore.jumpByPercent(-1),
  onStep: (multiplier) => {
    for (let i = 0; i < multiplier; i += 1) experimentsStore.jumpByPercent(-1)
  },
})
const jumpForward = useLongPress({
  onTap: () => experimentsStore.jumpByPercent(1),
  onStep: (multiplier) => {
    for (let i = 0; i < multiplier; i += 1) experimentsStore.jumpByPercent(1)
  },
})

function toggleReviewPlay(): void {
  const id = experimentsStore.current?.id
  if (!id) return
  if (experimentsStore.playbackMode === 'REVIEW_PLAYING') {
    experimentsStore.pauseReviewPlayback()
  } else {
    experimentsStore.startReviewPlayback()
  }
}

function returnToLive(): void {
  experimentsStore.returnToLive()
}

onBeforeUnmount(() => {
  jumpBack.dispose()
  jumpForward.dispose()
  if (moveRafId !== null && typeof cancelAnimationFrame === 'function') {
    cancelAnimationFrame(moveRafId)
    moveRafId = null
  }
})

function onCancelReplayJob(): void {
  experimentsStore.cancelReplayAndCleanup()
}

const replayProgress = computed(() => {
  const job = experimentsStore.activeReplayJob
  if (!job) return null
  if (job.status === 'COMPLETED') return 1
  return Math.min(1, Math.max(0, job.progress))
})
const replayBusy = computed(
  () =>
    experimentsStore.activeReplayJob?.status === 'QUEUED' ||
    experimentsStore.activeReplayJob?.status === 'RUNNING',
)

const cursorTimeLabel = computed(() => formatSimulationTime(experimentsStore.cursorTimeSeconds))
const currentTimeLabel = computed(() =>
  formatSimulationTime(
    experimentsStore.liveState?.simulationTimeSeconds ??
      experimentsStore.current?.progress.simulationTimeSeconds ??
      0,
  ),
)
const endTimeLabel = computed(() => {
  const experiment = experimentsStore.current
  if (!experiment) return '—'
  const target = experiment.config.targetSimulationTimeSeconds
  if (target !== null && target !== undefined) return formatSimulationTime(target)
  return `${formatInteger(totalRange.value)} 步`
})
</script>

<template>
  <div class="playback-timeline" data-testid="playback-timeline">
    <div class="playback-status-row">
      <span class="playback-mode" :class="isReviewing ? 'is-review' : 'is-live'">
        {{ PLAYBACK_MODE_LABELS[experimentsStore.playbackMode] }}
      </span>
      <span class="playback-precision" :class="`precision-${experimentsStore.playbackPrecision.toLowerCase()}`">
        精度：{{ PRECISION_LABELS[experimentsStore.playbackPrecision] }}
      </span>
      <span v-if="runningWhileReview" class="playback-running-hint">历史回看中，模拟仍在运行</span>
      <span v-else-if="experimentsStore.current" class="playback-status">{{ experimentsStore.current.status }}</span>
    </div>

    <div class="playback-times">
      <span>实时 <b>{{ currentTimeLabel }}</b>（步 {{ formatInteger(currentStep) }}）</span>
      <span v-if="isReviewing">游标 <b>{{ cursorTimeLabel }}</b>（步 {{ formatInteger(cursorStep) }}）</span>
      <span class="playback-end">计划终点 {{ endTimeLabel }}</span>
    </div>

    <div
      ref="trackRef"
      class="playback-track"
      role="slider"
      aria-label="历史时间轴"
      aria-valuemin="0"
      :aria-valuemax="totalRange"
      :aria-valuenow="cursorStep"
      :aria-valuetext="`游标步 ${formatInteger(cursorStep)}`"
      :tabindex="isReviewing ? 0 : -1"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
    >
      <div class="track-base"></div>
      <div class="track-archived" :style="{ left: `${archivedLeft * 100}%`, width: `${(archivedRight - archivedLeft) * 100}%` }"></div>
      <div class="track-future" :style="{ left: `${archivedRight * 100}%`, width: `${(1 - archivedRight) * 100}%` }"></div>
      <button
        class="track-current"
        :style="{ left: `${currentRatio * 100}%` }"
        :aria-label="`当前权威步 ${formatInteger(currentStep)}`"
        tabindex="-1"
      ></button>
      <button
        v-if="isReviewing"
        class="track-cursor"
        :style="{ left: `${cursorRatio * 100}%` }"
        :aria-label="`历史游标步 ${formatInteger(cursorStep)}`"
        tabindex="-1"
      ></button>
      <AppTooltip
        v-for="tick in eventTicks.slice(-80)"
        :key="`${tick.step}-${tick.events[0].eventId ?? tick.events[0].sequence}`"
        :text="tick.tooltip"
        :title="tick.events[0].type === 'DIAGNOSTIC' ? '运行时诊断' : '近距离事件'"
      >
        <button
          class="track-event"
          :class="{ 'is-diagnostic': tick.events[0].type === 'DIAGNOSTIC' }"
          :style="{ left: `${ratioOf(tick.step) * 100}%` }"
          :aria-label="tick.tooltip"
          @click.stop="onEventTick(tick)"
        ></button>
      </AppTooltip>
    </div>

    <div v-if="experimentsStore.activeReplayJob && replayBusy" class="replay-progress-row">
      <span>精确重建 {{ Math.round((replayProgress ?? 0) * 100) }}%</span>
      <div class="replay-progress"><i :style="{ width: `${(replayProgress ?? 0) * 100}%` }"></i></div>
      <button type="button" class="replay-cancel" @click="onCancelReplayJob">取消</button>
    </div>
    <div v-else-if="experimentsStore.activeReplayJob && experimentsStore.activeReplayJob.status === 'FAILED'" class="replay-progress-row">
      <span class="replay-error">精确重建失败</span>
      <button type="button" @click="experimentsStore.current && experimentsStore.enterReview(experimentsStore.current.id, cursorStep)">重试</button>
    </div>

    <div class="playback-controls">
      <AppTooltip text="向后跳转 1%（长按连续跳转）" :focusable="true">
        <button
          type="button"
          class="history-btn"
          aria-label="历史向后跳转"
          :disabled="!isReviewing"
          @pointerdown="jumpBack.onPointerDown"
          @pointerup="jumpBack.onPointerUp"
          @pointercancel="jumpBack.onPointerCancel"
        >⏮</button>
      </AppTooltip>
      <AppTooltip text="播放 / 暂停历史" :focusable="true">
        <button
          type="button"
          class="history-btn"
          aria-label="播放或暂停历史"
          :disabled="!isReviewing"
          @click="toggleReviewPlay"
        >{{ experimentsStore.playbackMode === 'REVIEW_PLAYING' ? '⏸' : '▶' }}</button>
      </AppTooltip>
      <AppTooltip text="向前跳转 1%（长按连续跳转）" :focusable="true">
        <button
          type="button"
          class="history-btn"
          aria-label="历史向前跳转"
          :disabled="!isReviewing"
          @pointerdown="jumpForward.onPointerDown"
          @pointerup="jumpForward.onPointerUp"
          @pointercancel="jumpForward.onPointerCancel"
        >⏭</button>
      </AppTooltip>
      <button v-if="isReviewing" type="button" class="history-btn return-live" @click="returnToLive">
        返回实时
      </button>
    </div>
  </div>
</template>