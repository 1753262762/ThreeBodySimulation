<script setup lang="ts">
import { computed } from 'vue'
import type { SimulationEvent } from '../contracts'
import { EVENT_TYPE_LABELS } from '../contracts'
import { formatScientific, formatSimulationTime } from '../lib/format'
import { fromSi, unitLabel } from '../lib/units'
import { useExperimentsStore } from '../stores/experiments'
import { usePreferencesStore } from '../stores/preferences'

const props = defineProps<{
  events: SimulationEvent[]
  selectedEventId: string | null
}>()

const emit = defineEmits<{
  (e: 'select', event: SimulationEvent): void
}>()

const experimentsStore = useExperimentsStore()
const preferences = usePreferencesStore()

const PHASE_LABELS: Record<string, string> = {
  ENTER: '进入',
  UPDATE: '更新',
  FINAL: '结束',
}
const SEVERITY_LABELS: Record<string, string> = {
  NOTICE: '提示',
  WARNING: '警告',
  CRITICAL: '严重',
}
const CAUSE_LABELS: Record<string, string> = {
  PHYSICAL_PHENOMENON: '物理现象',
  PARAMETER_RISK: '参数风险',
  NUMERICAL_ERROR: '数值误差',
  MIXED: '混合',
}
const DIAGNOSTIC_CODE_LABELS: Record<string, string> = {
  ENERGY_DRIFT: '能量漂移',
  POSSIBLE_ESCAPE: '可能逃逸',
  SUDDEN_DEFLECTION: '剧烈偏转',
  RAPID_DISASSEMBLY: '快速解体',
  NUMERICAL_INSTABILITY: '数值不稳定',
}

const unitSystem = computed(() => preferences.unitSystem)

/** 事件按创建顺序倒序，最近的排在最前。 */
const visibleEvents = computed(() => {
  const events = props.events.filter(
    (event) => event.type === 'NEAR_ENCOUNTER' || event.type === 'DIAGNOSTIC',
  )
  return [...events].sort((a, b) => b.sequence - a.sequence).slice(0, 50)
})

const bodyNameById = computed(() => {
  const map = new Map<string, string>()
  const config = experimentsStore.current?.config
  if (config) {
    for (const body of config.bodies) {
      if (body.id) map.set(body.id, body.name)
    }
  }
  return map
})

function bodyNames(event: SimulationEvent): string {
  const names = (event.bodyIds ?? []).map((id) => bodyNameById.value.get(id) ?? id)
  return names.length > 0 ? names.join('、') : '—'
}

function distanceText(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  return formatScientific(fromSi(value, 'length', unitSystem.value)) + ' ' + unitLabel('length', unitSystem.value)
}

function timeText(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds)) return '—'
  return formatSimulationTime(seconds)
}

function closestLabel(event: SimulationEvent): string {
  const closest = event.closestDistanceMeters
  if (closest === null || closest === undefined) {
    return '触发时记录，位置为近似'
  }
  return distanceText(closest)
}

interface EvidenceRow {
  label: string
  value: string
}

const EVIDENCE_LABELS: Record<string, string> = {
  timeStepSeconds: '时间步长',
  softeningLengthMeters: '软化长度',
  normalizedEnergyError: '归一化能量误差',
  relativeEnergyDrift: '相对能量漂移',
  minimumPairDistanceMeters: '最近间距',
  speedRatioToEscape: '速度 / 逃逸速度',
  directionChangeDegrees: '方向偏转',
  rmsRadiusRatio: '均方根半径比',
  outwardBodyFraction: '向外天体比例',
  lastStableStep: '最后稳定步',
}

function evidenceRows(event: SimulationEvent): EvidenceRow[] {
  const evidence = event.diagnostic?.evidence
  if (!evidence) return []
  const rows: EvidenceRow[] = []
  for (const [key, value] of Object.entries(evidence)) {
    if (key === 'bodyIds') continue
    const label = EVIDENCE_LABELS[key]
    if (!label) continue
    if (value === null || value === undefined) continue
    const text =
      key === 'timeStepSeconds' || key === 'softeningLengthMeters'
        ? distanceText(value as number)
        : key === 'lastStableStep'
          ? String(Math.round(value as number))
          : formatScientific(value as number)
    rows.push({ label, value: text })
  }
  return rows
}
</script>

<template>
  <div class="event-panel" data-testid="event-panel">
    <header class="event-panel-header">
      <b>事件与诊断</b>
      <span>{{ visibleEvents.length }} 条</span>
    </header>
    <div v-if="visibleEvents.length === 0" class="event-empty">暂无事件</div>
    <div v-else class="event-list">
      <article
        v-for="event in visibleEvents"
        :key="event.eventId ?? 'seq-' + event.sequence"
        class="event-card"
        :class="{
          'is-selected': event.eventId === selectedEventId || (event.eventId == null && ('seq-' + event.sequence) === selectedEventId),
          'is-diagnostic': event.type === 'DIAGNOSTIC',
        }"
      >
        <header class="event-card-header">
          <span class="event-type-badge">{{ EVENT_TYPE_LABELS[event.type] }}</span>
          <span v-if="event.phase" class="event-phase">{{ PHASE_LABELS[event.phase] ?? event.phase }}</span>
          <span
            v-if="event.diagnostic"
            class="event-severity"
            :class="'severity-' + event.diagnostic.severity.toLowerCase()"
          >
            {{ SEVERITY_LABELS[event.diagnostic.severity] ?? event.diagnostic.severity }}
          </span>
          <span class="event-time">{{ timeText(event.simulationTimeSeconds) }}</span>
        </header>
        <p class="event-message">{{ event.message }}</p>
        <dl class="event-facts">
          <template v-if="event.diagnostic">
            <div class="event-fact">
              <dt>原因类别</dt>
              <dd>{{ CAUSE_LABELS[event.diagnostic.causeCategory] ?? event.diagnostic.causeCategory }}</dd>
            </div>
            <div class="event-fact">
              <dt>诊断码</dt>
              <dd>{{ DIAGNOSTIC_CODE_LABELS[event.diagnostic.code] ?? event.diagnostic.code }}</dd>
            </div>
          </template>
          <div class="event-fact"><dt>涉及天体</dt><dd>{{ bodyNames(event) }}</dd></div>
          <div class="event-fact"><dt>触发距离</dt><dd>{{ distanceText(event.triggerDistanceMeters) }}</dd></div>
          <div class="event-fact"><dt>最近距离</dt><dd>{{ closestLabel(event) }}</dd></div>
          <div class="event-fact"><dt>阈值</dt><dd>{{ distanceText(event.thresholdMeters) }}</dd></div>
          <div class="event-fact"><dt>最近点时间</dt><dd>{{ timeText(event.closestSimulationTimeSeconds) }}</dd></div>
        </dl>
        <template v-if="event.diagnostic">
          <p class="event-diagnostic-summary">{{ event.diagnostic.summary }}</p>
          <div v-if="evidenceRows(event).length > 0" class="event-evidence">
            <span class="event-subtitle">证据</span>
            <dl class="event-facts">
              <div v-for="row in evidenceRows(event)" :key="row.label" class="event-fact">
                <dt>{{ row.label }}</dt>
                <dd>{{ row.value }}</dd>
              </div>
            </dl>
          </div>
          <div v-if="event.diagnostic.likelyCauses.length > 0" class="event-cause-list">
            <span class="event-subtitle">可能原因</span>
            <ul>
              <li v-for="(cause, i) in event.diagnostic.likelyCauses" :key="i">{{ cause }}</li>
            </ul>
          </div>
          <div v-if="event.diagnostic.recommendations.length > 0" class="event-cause-list">
            <span class="event-subtitle">建议</span>
            <ul>
              <li v-for="(recommendation, i) in event.diagnostic.recommendations" :key="i">{{ recommendation }}</li>
            </ul>
          </div>
        </template>
        <footer class="event-card-footer">
          <button type="button" class="event-locate" @click="emit('select', event)">定位到此处</button>
        </footer>
      </article>
    </div>
  </div>
</template>
