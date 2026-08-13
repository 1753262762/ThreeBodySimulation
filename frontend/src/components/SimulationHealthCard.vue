<script setup lang="ts">
import { computed, ref } from 'vue'
import type { SimulationHealthReport } from '../contracts'
import { formatPercent, formatScientific, formatSimulationTime } from '../lib/format'

const props = defineProps<{
  health: SimulationHealthReport | null
  reviewing?: boolean
  hideClone?: boolean
  defaultExpanded?: boolean
}>()

const emit = defineEmits<{
  cloneRetry: [suggestedTimeStepSeconds: number]
}>()

const expanded = ref(props.defaultExpanded ?? false)
const labels = {
  GOOD: '数值结果可信度良好',
  WARNING: '数值结果需要谨慎解读',
  POOR: '数值误差已经明显',
  FAILED: '计算在失败点之后不可用',
} as const
const trendLabels = {
  STABLE: '稳定',
  SLOWLY_INCREASING: '缓慢上升',
  RAPIDLY_INCREASING: '快速上升',
} as const
const suggestion = computed(() => props.health?.recommendations
  .find((item) => item.code === 'REDUCE_TIME_STEP')?.configPatch.timeStepSeconds ?? null)
const reasonLabels: Record<string, string> = {
  CONSERVATION_WITHIN_TOLERANCE: '采样到的守恒量漂移仍在项目默认容差内。',
  ENERGY_DRIFT_ELEVATED: '能量漂移峰值超过注意阈值，结果需要谨慎解读。',
  ENERGY_DRIFT_HIGH: '能量漂移峰值超过较差阈值，数值误差已经明显。',
  ANGULAR_MOMENTUM_DRIFT_ELEVATED: '角动量漂移峰值超过注意阈值。',
  ANGULAR_MOMENTUM_DRIFT_HIGH: '角动量漂移峰值超过较差阈值。',
  CLOSE_ENCOUNTER_NEAR_DRIFT: '漂移越级与近遇在时间上接近，但这并不证明近遇是原因。',
  SOFTENING_SCALE_APPROACHED: '最近距离接近软化尺度；这是模型提示，不单独降低可信度。',
  NON_FINITE_STATE: '位置或速度出现了计算机无法继续表示的数值。',
  NON_FINITE_METRICS: '派生指标出现了计算机无法继续表示的数值。',
}
const recommendation = computed(() => props.health?.recommendations
  .find((item) => item.code === 'REDUCE_TIME_STEP') ?? null)
</script>

<template>
  <article
    class="simulation-health-card"
    :class="[{ expanded }, health ? `health-status-${health.status.toLowerCase()}` : 'health-status-unavailable']"
  >
    <button class="health-card-summary" type="button" @click="expanded = !expanded" :aria-expanded="expanded">
      <span>数值结果可信度</span>
      <b>{{ health ? labels[health.status] : '尚无数据' }}</b>
      <em>{{ expanded ? '收起诊断' : '展开诊断' }}</em>
    </button>

    <div v-if="expanded" class="health-card-details">
      <p v-if="reviewing" class="health-scope-note">此诊断代表整个运行记录截至最新状态，不随回放游标变化。</p>
      <p v-if="!health" class="queue-empty">此运行记录没有完整的数值健康采样历史；可创建对照实验后重新运行。</p>
      <template v-else>
        <section class="health-explanation-layer">
          <h3>结论</h3>
          <p><b>{{ labels[health.status] }}</b></p>
          <p>这里评价的是积分计算的数值误差，不保证初始模型代表真实宇宙，也不会把天体逃逸或系统解体自动视为错误。</p>
          <p v-if="health.status === 'FAILED'">计算并不是因为“星系解体”而停止，而是因为位置、速度或派生指标出现了计算机无法继续表示的数值。当前结果只能使用到失败之前。</p>
        </section>
        <section class="health-explanation-layer">
          <h3>证据</h3>
        <dl class="health-metric-grid">
          <div><dt>能量漂移（当前 / 峰值）</dt><dd>{{ formatPercent(health.metrics.currentEnergyDrift) }} / {{ formatPercent(health.metrics.peakEnergyDrift) }}<small>观察本应近似守恒的系统能量是否出现不可忽略的数值变化。</small></dd></div>
          <div><dt>角动量漂移（当前 / 峰值）</dt><dd>{{ health.metrics.currentAngularMomentumDrift == null ? '不可用' : formatPercent(health.metrics.currentAngularMomentumDrift) }} / {{ health.metrics.peakAngularMomentumDrift == null ? '不可用' : formatPercent(health.metrics.peakAngularMomentumDrift) }}<small>同时比较大小和方向，避免净角动量接近零时制造巨大误差。</small></dd></div>
          <div><dt>趋势</dt><dd>能量 {{ trendLabels[health.metrics.energyTrend] }} · 角动量 {{ trendLabels[health.metrics.angularMomentumTrend] }}</dd></div>
          <div><dt>最近距离 / 近遇次数</dt><dd>{{ health.metrics.closestApproachMeters == null ? '—' : formatScientific(health.metrics.closestApproachMeters) + ' m' }} / {{ health.metrics.closeEncounterCount }}</dd></div>
        </dl>
        <ul v-if="health.reasons.length" class="health-reasons">
          <li v-for="reason in health.reasons" :key="reason.code" :data-severity="reason.severity">
            {{ reasonLabels[reason.code] ?? reason.message }}
          </li>
        </ul>
        <div v-if="health.failure" class="health-failure">
          <b>{{ health.failure.code }}</b>
          <span>{{ health.failure.bodyId ?? 'system' }} · {{ health.failure.field ?? 'state' }} · step {{ health.failure.step }} · {{ formatSimulationTime(health.failure.simulationTimeSeconds) }}</span>
          <span>{{ health.failure.value ?? '—' }} · {{ health.failure.message }}</span>
        </div>
        </section>
        <section v-if="recommendation" class="health-explanation-layer">
          <h3>怎么验证</h3>
          <p>{{ recommendation.message }}</p>
          <p v-if="recommendation.rationale"><b>收益：</b>{{ recommendation.rationale }}</p>
          <p v-if="recommendation.tradeoff"><b>代价：</b>{{ recommendation.tradeoff }}</p>
          <p v-if="recommendation.verification"><b>比较方法：</b>{{ recommendation.verification }}</p>
        </section>
        <button
          v-if="suggestion != null && !hideClone"
          class="apply-button compact health-clone-button"
          type="button"
          @click.stop="emit('cloneRetry', suggestion)"
        >
          创建对照实验（建议 dt {{ suggestion }} s）
        </button>
        <details class="health-technical-details">
          <summary>技术详情</summary>
          <dl class="health-metric-grid">
            <div><dt>采样</dt><dd>{{ health.sampleCount.toLocaleString() }} 点 · 每 {{ health.sampleStride.toLocaleString() }} 步</dd></div>
            <div><dt>分析到</dt><dd>{{ health.analyzedStep.toLocaleString() }} 步 · {{ formatSimulationTime(health.analyzedSimulationTimeSeconds) }}</dd></div>
            <div><dt>项目默认容差（注意 / 较差）</dt><dd>能量 {{ formatPercent(health.thresholds.energyWarning) }} / {{ formatPercent(health.thresholds.energyPoor) }} · 角动量 {{ formatPercent(health.thresholds.angularMomentumWarning) }} / {{ formatPercent(health.thresholds.angularMomentumPoor) }}</dd></div>
            <div><dt>reason code</dt><dd>{{ health.reasons.map((reason) => reason.code).join(' · ') || '—' }}</dd></div>
          </dl>
        </details>
      </template>
    </div>
  </article>
</template>
