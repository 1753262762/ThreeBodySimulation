<script setup lang="ts">
import { ref, watch } from 'vue'
import type { ComparisonExperimentPlan, StepLimitResolution } from '../lib/comparisonExperiment'
import { formatScientific, formatSimulationTime } from '../lib/format'

const props = defineProps<{
  open: boolean
  plan: ComparisonExperimentPlan | null
  rationale?: string | null
  tradeoff?: string | null
  context?: 'comparison' | 'guidance'
}>()

const emit = defineEmits<{
  (event: 'confirm', resolution: StepLimitResolution | null): void
  (event: 'cancel'): void
}>()

const resolution = ref<StepLimitResolution>('SHORTEN_DURATION')
watch(() => props.open, (open) => {
  if (open) resolution.value = 'SHORTEN_DURATION'
})
</script>

<template>
  <Teleport to="body">
    <div v-if="open && plan" class="modal-backdrop" @click.self="emit('cancel')">
      <section class="warning-modal comparison-dialog" role="dialog" aria-modal="true" aria-labelledby="comparison-title">
        <h2 id="comparison-title">创建对照实验</h2>
        <p class="modal-hint">{{ context === 'guidance' ? '当前建议会改变数值分辨率；确认后仍可在参数编辑器检查。' : '只先调整数值分辨率，源运行记录会完整保留；确认后仍可在参数编辑器检查并修改。' }}</p>
        <dl class="comparison-diff">
          <div><dt>时间步长</dt><dd>{{ formatScientific(plan.originalTimeStepSeconds) }} s → {{ formatScientific(plan.proposedTimeStepSeconds) }} s</dd></div>
          <div><dt>最大步数</dt><dd>{{ plan.originalMaxSteps ?? '未设置' }} → {{ plan.proposedMaxSteps ?? '未设置' }}</dd></div>
          <div><dt>模拟覆盖时间</dt><dd>{{ formatSimulationTime(plan.originalSimulationTimeSeconds) }} → {{ formatSimulationTime(plan.proposedSimulationTimeSeconds) }}</dd></div>
          <div><dt>预计积分步数</dt><dd>{{ plan.originalEstimatedSteps.toLocaleString() }} → {{ plan.proposedEstimatedSteps.toLocaleString() }}</dd></div>
          <div><dt>相对计算量</dt><dd>约 {{ plan.relativeStepCount.toFixed(2) }} 倍（按积分步数）</dd></div>
        </dl>
        <p v-if="rationale"><b>为什么这样改：</b>{{ rationale }}</p>
        <p v-if="tradeoff"><b>代价：</b>{{ tradeoff }}</p>
        <fieldset v-if="plan.exceedsStepLimit" class="comparison-limit-options">
          <legend>保持原模拟时长会超过 100,000,000 步，请选择：</legend>
          <label><input v-model="resolution" type="radio" value="SHORTEN_DURATION">保留建议时间步长，缩短模拟覆盖时间</label>
          <label><input v-model="resolution" type="radio" value="PRESERVE_DURATION">使用能保持原覆盖时间的最小合法时间步长</label>
          <span>也可以取消并在参数编辑器中手动调整。</span>
        </fieldset>
        <div class="modal-actions">
          <button type="button" class="secondary" @click="emit('cancel')">取消</button>
          <button type="button" @click="emit('confirm', plan.exceedsStepLimit ? resolution : null)">{{ context === 'guidance' ? '应用所选调整' : '载入参数编辑器' }}</button>
        </div>
      </section>
    </div>
  </Teleport>
</template>
