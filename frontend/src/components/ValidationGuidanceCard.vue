<script setup lang="ts">
import type { GuidanceAction, ValidationIssue } from '../contracts'
import { formatScientific } from '../lib/format'

defineProps<{
  issue: ValidationIssue
  compact?: boolean
}>()

const emit = defineEmits<{
  (event: 'apply', action: GuidanceAction): void
}>()

const EVIDENCE_LABELS: Record<string, string> = {
  TIME_STEP_SECONDS: '当前时间步长',
  LOCAL_PERIOD_SECONDS: '最快局部周期',
  ONE_STEP_RELATIVE_MOVEMENT_METERS: '单步相对位移',
  SPEED_TO_ESCAPE_RATIO: '速度 / 逃逸尺度',
  SOFTENING_LENGTH_METERS: '软化长度',
  INITIAL_PAIR_DISTANCE_METERS: '初始最近距离',
}
</script>

<template>
  <article class="validation-guidance" :class="{ 'is-compact': compact }">
    <header>
      <span class="risk-badge">{{ issue.riskLevel === 'HIGH' ? '高风险' : '注意' }}</span>
      <b>{{ issue.guidance?.observation ?? issue.message }}</b>
    </header>
    <template v-if="issue.guidance">
      <p><strong>可能影响：</strong>{{ issue.guidance.impact }}</p>
      <ul v-if="issue.guidance.evidence.length" class="guidance-evidence">
        <li v-for="evidence in issue.guidance.evidence" :key="`${evidence.code}-${evidence.value}`">
          {{ EVIDENCE_LABELS[evidence.code] ?? evidence.code }}：{{ formatScientific(evidence.value) }}
          <span v-if="evidence.ratio != null">（比例 {{ formatScientific(evidence.ratio) }}）</span>
        </li>
      </ul>
      <div class="guidance-action">
        <b>首选：{{ issue.guidance.primaryAction.label }}</b>
        <p>{{ issue.guidance.primaryAction.rationale }}</p>
        <small>代价：{{ issue.guidance.primaryAction.tradeoff }}</small>
        <button
          v-if="issue.guidance.primaryAction.mode === 'APPLY_PATCH'"
          type="button"
          @click="emit('apply', issue.guidance.primaryAction)"
        >应用建议</button>
      </div>
      <details v-if="issue.guidance.alternatives.length">
        <summary>替代方案</summary>
        <div v-for="action in issue.guidance.alternatives" :key="action.code" class="guidance-alternative">
          <b>{{ action.label }}</b>
          <span>{{ action.rationale }}</span>
          <small>代价：{{ action.tradeoff }}</small>
        </div>
      </details>
    </template>
  </article>
</template>
