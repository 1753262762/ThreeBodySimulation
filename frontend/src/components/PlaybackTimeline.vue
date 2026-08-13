<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useExperimentsStore } from '../stores/experiments'
import { usePreferencesStore } from '../stores/preferences'
import { formatSimulationTime, formatInteger, formatScientific } from '../lib/format'
import {
  REPLAY_RATES,
  timelineRatioFromStep,
  timelineStepFromRatio,
  type ReplayRate,
} from '../lib/playbackTimeline'
import { fromSi, type UnitSystem } from '../lib/units'
import type { SimulationEvent } from '../contracts'
import AppTooltip from './AppTooltip.vue'

const experimentsStore = useExperimentsStore()
const preferences = usePreferencesStore()

const timelineRef = ref<HTMLElement | null>(null)
const trackRef = ref<HTMLElement | null>(null)
const scrubbing = ref(false)

const PLAYBACK_MODE_LABELS: Record<string, string> = {
  LIVE: 'LIVE',
  REVIEW_PAUSED: 'REVIEW',
  REVIEW_PLAYING: 'PLAY',
}
const PRECISION_LABELS: Record<string, string> = {
  EXACT: '精确',
  APPROXIMATE: '预览',
  RESOLVING: '解析中',
  ERROR: '解析失败',
}

const currentStep = computed(() => experimentsStore.liveState?.step ?? experimentsStore.current?.progress.step ?? 0)
const cursorStep = computed(() => experimentsStore.isReviewing ? experimentsStore.cursorStep : currentStep.value)
const upper = computed(() => Math.max(0, currentStep.value))
const lower = computed(() => Math.min(experimentsStore.availableFromStep ?? 0, upper.value))
const isReviewing = computed(() => experimentsStore.isReviewing)
const runningWhileReview = computed(
  () => isReviewing.value && experimentsStore.current?.status === 'RUNNING',
)

function ratioOf(step: number): number {
  return timelineRatioFromStep(step, lower.value, upper.value)
}

const cursorRatio = computed(() => ratioOf(cursorStep.value))

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
    if (step < lower.value || step > upper.value) continue
    const list = byStep.get(step)
    if (list) list.push(event)
    else byStep.set(step, [event])
  }
  return [...byStep.entries()]
    .map(([step, events]) => ({
      step,
      events,
      tooltip: events.map((event) => {
        const type = event.type === 'DIAGNOSTIC' ? '诊断' : '近距离'
        const phase = event.phase ? `·${event.phase}` : ''
        const distance = event.closestDistanceMeters ?? event.distanceMeters
        const distanceText = distance !== null && distance !== undefined
          ? `，最近 ${formatScientific(fromSi(distance, 'length', unitSystem()))} ${unitSystem() === 'ASTRONOMICAL' ? 'AU' : 'm'}`
          : ''
        return `${type}${phase} @步 ${formatInteger(step)}${distanceText}`
      }).join('；'),
    }))
    .sort((a, b) => a.step - b.step)
})

const rulerTicks = computed(() => [0, 0.25, 0.5, 0.75, 1].map((ratio) => {
  const step = timelineStepFromRatio(ratio, lower.value, upper.value)
  const seconds = step * (experimentsStore.current?.config.timeStepSeconds ?? 0)
  return { ratio, label: formatSimulationTime(seconds) }
}))

function unitSystem(): UnitSystem {
  return preferences.unitSystem
}

function stepFromEvent(event: PointerEvent): number {
  const track = trackRef.value
  if (!track) return cursorStep.value
  const rect = track.getBoundingClientRect()
  if (rect.width <= 0) return cursorStep.value
  return timelineStepFromRatio((event.clientX - rect.left) / rect.width, lower.value, upper.value)
}

let moveRafId: number | null = null
let pendingMoveStep: number | null = null

function applyPendingMove(): void {
  if (pendingMoveStep === null) return
  experimentsStore.seekCursorFromCache(pendingMoveStep)
  pendingMoveStep = null
}

function onPointerDown(event: PointerEvent): void {
  if (event.button !== 0 || !experimentsStore.current || upper.value <= lower.value) return
  scrubbing.value = true
  ;(event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId)
  experimentsStore.beginReviewScrub(stepFromEvent(event))
}

function onPointerMove(event: PointerEvent): void {
  if (!scrubbing.value) return
  pendingMoveStep = stepFromEvent(event)
  if (moveRafId !== null || typeof requestAnimationFrame !== 'function') return
  moveRafId = requestAnimationFrame(() => {
    moveRafId = null
    applyPendingMove()
  })
}

function onPointerUp(event: PointerEvent): void {
  if (!scrubbing.value) return
  scrubbing.value = false
  ;(event.currentTarget as HTMLElement).releasePointerCapture?.(event.pointerId)
  if (moveRafId !== null && typeof cancelAnimationFrame === 'function') {
    cancelAnimationFrame(moveRafId)
    moveRafId = null
  }
  applyPendingMove()
  experimentsStore.settleReviewCursor()
}

function onEventTick(tick: EventTick): void {
  const id = experimentsStore.current?.id
  const event = tick.events[0]
  if (id && event) void experimentsStore.locateEvent(id, event)
}

function togglePrimary(): void {
  if (isReviewing.value) {
    if (experimentsStore.playbackMode === 'REVIEW_PLAYING') experimentsStore.pauseReviewPlayback()
    else experimentsStore.startReviewPlayback()
    return
  }
  if (experimentsStore.can('PAUSE')) void experimentsStore.submitAction('PAUSE')
  else if (experimentsStore.can('RESUME')) void experimentsStore.submitAction('RESUME')
}

function stepBackward(): void {
  experimentsStore.stepReviewFrame(-1)
}

function stepForward(): void {
  if (isReviewing.value) experimentsStore.stepReviewFrame(1)
  else if (experimentsStore.can('STEP')) void experimentsStore.submitAction('STEP')
}

function onRateChange(event: Event): void {
  experimentsStore.setReplayRate(Number((event.target as HTMLSelectElement).value) as ReplayRate)
}

function onTimelineKeydown(event: KeyboardEvent): void {
  const target = event.target
  if (target !== timelineRef.value) return
  if (event.code === 'Space') {
    event.preventDefault()
    togglePrimary()
  } else if (event.key === 'ArrowLeft') {
    event.preventDefault()
    stepBackward()
  } else if (event.key === 'ArrowRight' && isReviewing.value) {
    event.preventDefault()
    stepForward()
  }
}

function retryExactResolution(): void {
  const id = experimentsStore.current?.id
  if (id) void experimentsStore.requestExactStep(id, cursorStep.value)
}

onBeforeUnmount(() => {
  if (moveRafId !== null && typeof cancelAnimationFrame === 'function') cancelAnimationFrame(moveRafId)
})

const replayBusy = computed(() => (
  experimentsStore.activeReplayJob?.status === 'QUEUED' ||
  experimentsStore.activeReplayJob?.status === 'RUNNING'
))
const replayProgressPercent = computed(() => Math.round(
  Math.min(1, Math.max(0, experimentsStore.activeReplayJob?.progress ?? 0)) * 100,
))
const liveTimeLabel = computed(() => formatSimulationTime(
  experimentsStore.liveState?.simulationTimeSeconds ?? experimentsStore.current?.progress.simulationTimeSeconds ?? 0,
))
const cursorTimeLabel = computed(() => isReviewing.value
  ? formatSimulationTime(experimentsStore.cursorTimeSeconds)
  : liveTimeLabel.value)
const endTimeLabel = computed(() => {
  const experiment = experimentsStore.current
  if (!experiment) return '—'
  const target = experiment.config.targetSimulationTimeSeconds
  return target !== null && target !== undefined
    ? formatSimulationTime(target)
    : `${formatInteger(experimentsStore.plannedEndStep)} 步`
})
const primaryDisabled = computed(() => {
  if (!experimentsStore.current) return true
  if (isReviewing.value) return false
  return !experimentsStore.can('PAUSE') && !experimentsStore.can('RESUME')
})
const primaryLabel = computed(() => {
  if (isReviewing.value) return experimentsStore.playbackMode === 'REVIEW_PLAYING' ? '暂停历史回放' : '播放历史'
  return experimentsStore.can('PAUSE') ? '暂停模拟' : '继续模拟'
})
</script>

<template>
  <div
    ref="timelineRef"
    class="playback-timeline"
    data-testid="playback-timeline"
    tabindex="0"
    aria-label="Replay 时间线"
    @keydown="onTimelineKeydown"
  >
    <div class="timeline-toolbar">
      <div class="timeline-status-cluster">
        <span class="playback-mode" :class="isReviewing ? 'is-review' : 'is-live'">
          {{ PLAYBACK_MODE_LABELS[experimentsStore.playbackMode] }}
        </span>
        <span v-if="isReviewing" class="playback-precision" :class="`precision-${experimentsStore.playbackPrecision.toLowerCase()}`">
          {{ PRECISION_LABELS[experimentsStore.playbackPrecision] }}
        </span>
        <span v-if="runningWhileReview" class="playback-running-hint">模拟仍在运行</span>
        <span v-else-if="experimentsStore.current" class="playback-status">{{ experimentsStore.current.status }}</span>
      </div>
      <div class="timeline-timecode" aria-label="当前时间码">{{ cursorTimeLabel }}</div>
      <div class="timeline-plan">计划终点 {{ endTimeLabel }}</div>
    </div>

    <div class="timeline-ruler" aria-hidden="true">
      <span v-for="tick in rulerTicks" :key="tick.ratio" :style="{ left: `${tick.ratio * 100}%` }">{{ tick.label }}</span>
    </div>

    <div
      ref="trackRef"
      class="playback-track"
      role="slider"
      aria-label="历史时间轴"
      :aria-valuemin="lower"
      :aria-valuemax="upper"
      :aria-valuenow="cursorStep"
      :aria-valuetext="`播放头 ${cursorTimeLabel}，步 ${formatInteger(cursorStep)}`"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
    >
      <div class="timeline-clip"></div>
      <div class="timeline-live-edge" aria-hidden="true"></div>
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
      <div class="timeline-playhead" :style="{ left: `${cursorRatio * 100}%` }" aria-hidden="true">
        <i></i>
      </div>
    </div>

    <div class="timeline-footer">
      <div class="timeline-position">
        <span>{{ isReviewing ? '游标' : '实时' }} {{ formatInteger(cursorStep) }}</span>
        <span>最新 {{ formatInteger(currentStep) }}</span>
      </div>

      <div class="playback-controls" role="group" aria-label="时间线运输控制">
        <AppTooltip text="后退一帧" :focusable="true">
          <button type="button" class="history-btn" aria-label="后退一帧" :disabled="!experimentsStore.current || upper <= lower" @click="stepBackward">◀│</button>
        </AppTooltip>
        <AppTooltip :text="primaryLabel" :focusable="true">
          <button type="button" class="history-btn primary" :aria-label="primaryLabel" :disabled="primaryDisabled" @click="togglePrimary">
            {{ (isReviewing && experimentsStore.playbackMode === 'REVIEW_PLAYING') || (!isReviewing && experimentsStore.can('PAUSE')) ? 'Ⅱ' : '▶' }}
          </button>
        </AppTooltip>
        <AppTooltip :text="isReviewing ? '前进一帧' : '模拟单步'" :focusable="true">
          <button type="button" class="history-btn" :aria-label="isReviewing ? '前进一帧' : '模拟单步'" :disabled="isReviewing ? cursorStep >= upper : !experimentsStore.can('STEP')" @click="stepForward">│▶</button>
        </AppTooltip>
        <label class="replay-rate-label">
          <span>速度</span>
          <select aria-label="Replay 倍速" :value="experimentsStore.replayRate" @change="onRateChange">
            <option v-for="rate in REPLAY_RATES" :key="rate" :value="rate">{{ rate }}×</option>
          </select>
        </label>
        <button v-if="isReviewing" type="button" class="history-btn return-live" @click="experimentsStore.returnToLive">返回实时</button>
      </div>

      <div class="replay-resolution" aria-live="polite">
        <template v-if="replayBusy">
          <span>解析 {{ replayProgressPercent }}%</span>
          <button type="button" class="replay-cancel" @click="experimentsStore.cancelReplayAndCleanup">取消</button>
        </template>
        <template v-else-if="experimentsStore.playbackPrecision === 'ERROR' && isReviewing">
          <span class="replay-error">解析失败</span>
          <button type="button" class="replay-cancel" @click="retryExactResolution">重试</button>
        </template>
      </div>
    </div>
  </div>
</template>
