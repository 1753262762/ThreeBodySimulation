<script setup lang="ts">
import { computed, ref } from 'vue'
import { useDraftStore } from '../stores/draft'
import { MAX_BODY_COUNT, MIN_BODY_COUNT } from '../contracts'
import { fromSi, unitLabel } from '../lib/units'
import { getParameterHelp } from '../lib/parameterHelp'
import { formatScientific, formatSimulationTime } from '../lib/format'
import AppTooltip from './AppTooltip.vue'
import ParameterHelpBlock from './ParameterHelpBlock.vue'
import ValidationGuidanceCard from './ValidationGuidanceCard.vue'

const draftStore = useDraftStore()
const expandedRow = ref<string | null>(null)
const showImport = ref(false)
const importText = ref('')
const importError = ref('')

const unitSystem = computed(() => draftStore.unitSystem)
const draft = computed(() => draftStore.draft)
const issuesByField = computed(() => draftStore.issuesByField)

/** 参数帮助集中来自 parameterHelp，随单位制切换动态刷新。 */
const help = computed(() => ({
  mass: getParameterHelp('mass', unitSystem.value),
  position: getParameterHelp('position', unitSystem.value),
  velocity: getParameterHelp('velocity', unitSystem.value),
  timeStep: getParameterHelp('timeStep', unitSystem.value),
  softeningLength: getParameterHelp('softeningLength', unitSystem.value),
  totalTime: getParameterHelp('totalTime', unitSystem.value),
  maxSteps: getParameterHelp('maxSteps', unitSystem.value),
  gravitationalConstant: getParameterHelp('gravitationalConstant', unitSystem.value),
}))

function fieldError(field: string): string | undefined {
  const list = (issuesByField.value.get(field) ?? []).filter((item) => item.severity === 'ERROR')
  return list[0]?.message
}

function fieldWarningIssues(field: string) {
  return (issuesByField.value.get(field) ?? [])
    .filter((item) => item.severity === 'WARNING')
}

function fieldWarnings(field: string): string[] {
  return fieldWarningIssues(field).filter((item) => !item.guidance).map((item) => item.message)
}

/** 聚合路径如 bodies[2].position 映射到该组所有坐标输入并在组下展示。 */
function groupError(prefix: string): string | undefined {
  for (const [field, items] of issuesByField.value) {
    if (field === prefix || field.startsWith(`${prefix}.`) || field.startsWith(`${prefix}[`)) {
      const error = items.find((item) => item.severity === 'ERROR')
      if (error) return error.message
    }
  }
  return undefined
}

function groupWarnings(prefix: string): string[] {
  const result: string[] = []
  for (const [field, items] of issuesByField.value) {
    if (field === prefix || field.startsWith(`${prefix}.`) || field.startsWith(`${prefix}[`)) {
      for (const item of items) {
        if (item.severity === 'WARNING' && !item.guidance) result.push(item.message)
      }
    }
  }
  return result
}

function groupWarningIssues(prefix: string) {
  const result = [] as ReturnType<typeof fieldWarningIssues>
  for (const [field, items] of issuesByField.value) {
    if (field === prefix || field.startsWith(`${prefix}.`) || field.startsWith(`${prefix}[`)) {
      result.push(...items.filter((item) => item.severity === 'WARNING' && item.guidance))
    }
  }
  return result
}

const configSummary = computed(() => draftStore.serverValidation?.configSummary ?? null)
const normalizedBodies = computed(() => draftStore.serverValidation?.normalizedConfig?.bodies ?? [])

function bodyNames(ids: string[]): string {
  return ids.map((id) => normalizedBodies.value.find((body) => body.id === id)?.name ?? id).join(' 与 ')
}

function bodyField(body: { rowId: string }, suffix: string): string {
  const index = draft.value.bodies.findIndex((item) => item.rowId === body.rowId)
  return `bodies[${Math.max(0, index)}].${suffix}`
}

function toggleExpand(rowId: string): void {
  expandedRow.value = expandedRow.value === rowId ? null : rowId
}

function doImport(): void {
  const result = draftStore.importFromJson(importText.value)
  if (result.ok) {
    showImport.value = false
    importText.value = ''
    importError.value = ''
  } else {
    importError.value = result.message
  }
}

function doExport(): void {
  const blob = new Blob([draftStore.exportToJson()], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `three-body-config-${new Date().toISOString().slice(0, 10)}.json`
  link.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <div class="lab-parameters scrollable">
    <div class="lab-title">
      <span>实验参数</span>
      <b :class="{ 'is-dirty': draftStore.isDirty }">{{ draftStore.isDirty ? '未保存' : '已保存' }}</b>
    </div>

    <div v-if="draftStore.presetsLoading" class="muted-hint">正在加载内置方案…</div>
    <div v-else-if="draftStore.presetsError" class="action-error">{{ draftStore.presetsError }}</div>
    <div v-else class="field">
      <label for="preset-select">初始配置</label>
      <select
        id="preset-select"
        :value="draftStore.selectedPresetKey ?? ''"
        @change="draftStore.applyPreset(($event.target as HTMLSelectElement).value)"
      >
        <option value="" disabled>选择内置方案…</option>
        <option v-for="preset in draftStore.presets" :key="preset.key" :value="preset.key">
          {{ preset.key }} · {{ preset.name }}
        </option>
      </select>
    </div>

    <div class="field" :class="{ 'is-error': fieldError('name') }">
      <label for="exp-name">实验名称</label>
      <input id="exp-name" v-model="draft.name" placeholder="例如：混沌轨道稳定性" />
      <small v-if="fieldError('name')" class="field-error">{{ fieldError('name') }}</small>
    </div>

    <div class="field-row">
      <div class="field" :class="{ 'is-error': fieldError('timeStepSeconds') }">
        <AppTooltip :title="'时间步长'" :text="`${help.timeStep.meaning}${help.timeStep.impact}${help.timeStep.range}`">
          <label for="time-step">时间步长</label>
        </AppTooltip>
        <input id="time-step" v-model="draft.timeStep" />
        <small class="param-unit">{{ unitLabel('time', unitSystem) }}</small>
        <ParameterHelpBlock :help="help.timeStep" />
        <small v-if="fieldError('timeStepSeconds')" class="field-error">{{ fieldError('timeStepSeconds') }}</small>
        <ValidationGuidanceCard
          v-for="issue in fieldWarningIssues('timeStepSeconds')"
          :key="issue.code"
          :issue="issue"
          compact
          @apply="draftStore.applyGuidanceAction"
        />
      </div>
      <div class="field" :class="{ 'is-error': fieldError('gravitationalConstant') }">
        <AppTooltip :title="'引力常数'" :text="`${help.gravitationalConstant.meaning}${help.gravitationalConstant.impact}${help.gravitationalConstant.range}`">
          <label for="grav-const">引力常数</label>
        </AppTooltip>
        <input id="grav-const" v-model="draft.gravitationalConstant" />
        <small>N·m²/kg²</small>
        <ParameterHelpBlock :help="help.gravitationalConstant" />
        <small v-if="fieldError('gravitationalConstant')" class="field-error">{{ fieldError('gravitationalConstant') }}</small>
        <small v-for="(message, i) in fieldWarnings('gravitationalConstant')" :key="`w-${i}`" class="field-warning">{{ message }}</small>
      </div>
    </div>

    <div class="field" :class="{ 'is-error': fieldError('softeningLengthMeters') }">
      <AppTooltip :title="'软化长度'" :text="`${help.softeningLength.meaning}${help.softeningLength.impact}${help.softeningLength.range}`">
        <label for="softening">软化长度</label>
      </AppTooltip>
      <input id="softening" v-model="draft.softeningLength" />
      <small class="param-unit">{{ unitLabel('length', unitSystem) }}</small>
      <ParameterHelpBlock :help="help.softeningLength" />
      <small v-if="fieldError('softeningLengthMeters')" class="field-error">{{ fieldError('softeningLengthMeters') }}</small>
      <ValidationGuidanceCard
        v-for="issue in fieldWarningIssues('softeningLengthMeters')"
        :key="issue.code"
        :issue="issue"
        compact
        @apply="draftStore.applyGuidanceAction"
      />
    </div>

    <div class="parameter-section">
      <header>
        <span>天体参数（{{ draft.bodies.length }}/{{ MAX_BODY_COUNT }}）</span>
        <div>
          <button type="button" @click="draftStore.addBody()" :disabled="draft.bodies.length >= MAX_BODY_COUNT">＋ 添加</button>
          <button type="button" @click="showImport = !showImport">导入</button>
          <button type="button" @click="doExport">导出</button>
        </div>
      </header>

      <div v-if="showImport" class="import-box">
        <textarea v-model="importText" rows="5" placeholder="粘贴配置 JSON…"></textarea>
        <div class="import-actions">
          <button type="button" @click="doImport">载入</button>
          <button type="button" @click="showImport = false">取消</button>
        </div>
        <small v-if="importError" class="field-error">{{ importError }}</small>
      </div>

      <div class="body-list">
        <div v-for="(body, index) in draft.bodies" :key="body.rowId" class="body-row">
          <span class="body-orb" :style="{ '--body-color': body.color }"></span>
          <div class="body-copy">
            <strong>{{ body.name || `天体 ${index + 1}` }}</strong>
            <span>{{ body.mass }} {{ unitLabel('mass', unitSystem) }}</span>
          </div>
          <div class="body-actions">
            <AppTooltip :text="expandedRow === body.rowId ? '收起天体编辑' : '编辑天体参数'" :focusable="false">
              <button type="button" :aria-label="expandedRow === body.rowId ? '收起天体编辑' : '编辑天体参数'" :aria-expanded="expandedRow === body.rowId" @click="toggleExpand(body.rowId)">✎</button>
            </AppTooltip>
            <AppTooltip text="复制天体" :focusable="true">
              <button type="button" aria-label="复制天体" @click="draftStore.duplicateBody(body.rowId)" :disabled="draft.bodies.length >= MAX_BODY_COUNT">⧉</button>
            </AppTooltip>
            <AppTooltip text="删除天体" :focusable="true">
              <button type="button" class="danger" aria-label="删除天体" @click="draftStore.removeBody(body.rowId)" :disabled="draft.bodies.length <= MIN_BODY_COUNT">✕</button>
            </AppTooltip>
          </div>
        </div>
      </div>

      <div v-if="expandedRow" class="body-editor">
        <template v-for="body in draft.bodies" :key="body.rowId">
          <template v-if="body.rowId === expandedRow">
            <div class="body-editor-header">
              <input type="color" v-model="body.color" />
              <input type="text" v-model="body.name" :placeholder="`天体 ${draft.bodies.indexOf(body) + 1} 名称`" />
            </div>
            <small v-if="fieldError(bodyField(body, 'name'))" class="field-error">{{ fieldError(bodyField(body, 'name')) }}</small>
            <div class="coord-grid">
              <div class="field" :class="{ 'is-error': fieldError(bodyField(body, 'massKg')) }">
                <AppTooltip :title="'天体质量'" :text="`${help.mass.meaning}${help.mass.impact}${help.mass.range}`">
                  <label>质量</label>
                </AppTooltip>
                <input v-model="body.mass" />
                <small class="param-unit">{{ unitLabel('mass', unitSystem) }}</small>
                <ParameterHelpBlock :help="help.mass" />
                <small v-if="fieldError(bodyField(body, 'massKg'))" class="field-error">{{ fieldError(bodyField(body, 'massKg')) }}</small>
              </div>
            </div>
            <div class="param-group-title">
              <AppTooltip :title="'初始位置'" :text="`${help.position.meaning}${help.position.impact}${help.position.range}`">
                <span>初始位置</span>
              </AppTooltip>
              <small class="param-unit">{{ unitLabel('length', unitSystem) }}</small>
            </div>
            <ParameterHelpBlock :help="help.position" />
            <small v-if="groupError(bodyField(body, 'position'))" class="field-error">{{ groupError(bodyField(body, 'position')) }}</small>
            <small v-for="(message, i) in groupWarnings(bodyField(body, 'position'))" :key="`pw-${i}`" class="field-warning">{{ message }}</small>
            <ValidationGuidanceCard
              v-for="issue in groupWarningIssues(bodyField(body, 'position'))"
              :key="issue.code"
              :issue="issue"
              compact
              @apply="draftStore.applyGuidanceAction"
            />
            <div class="coord-grid">
              <div class="field" :class="{ 'is-error': fieldError(bodyField(body, 'position.x')) }">
                <label>x</label><input v-model="body.positionX" />
                <small v-if="fieldError(bodyField(body, 'position.x'))" class="field-error">{{ fieldError(bodyField(body, 'position.x')) }}</small>
              </div>
              <div class="field" :class="{ 'is-error': fieldError(bodyField(body, 'position.y')) }">
                <label>y</label><input v-model="body.positionY" />
                <small v-if="fieldError(bodyField(body, 'position.y'))" class="field-error">{{ fieldError(bodyField(body, 'position.y')) }}</small>
              </div>
              <div class="field" :class="{ 'is-error': fieldError(bodyField(body, 'position.z')) }">
                <label>z</label><input v-model="body.positionZ" />
                <small v-if="fieldError(bodyField(body, 'position.z'))" class="field-error">{{ fieldError(bodyField(body, 'position.z')) }}</small>
              </div>
            </div>
            <div class="param-group-title">
              <AppTooltip :title="'初始速度'" :text="`${help.velocity.meaning}${help.velocity.impact}${help.velocity.range}`">
                <span>初始速度</span>
              </AppTooltip>
              <small class="param-unit">{{ unitLabel('velocity', unitSystem) }}</small>
            </div>
            <ParameterHelpBlock :help="help.velocity" />
            <small v-if="groupError(bodyField(body, 'velocity'))" class="field-error">{{ groupError(bodyField(body, 'velocity')) }}</small>
            <small v-for="(message, i) in groupWarnings(bodyField(body, 'velocity'))" :key="`vw-${i}`" class="field-warning">{{ message }}</small>
            <ValidationGuidanceCard
              v-for="issue in groupWarningIssues(bodyField(body, 'velocity'))"
              :key="issue.code"
              :issue="issue"
              compact
              @apply="draftStore.applyGuidanceAction"
            />
            <div class="coord-grid">
              <div class="field" :class="{ 'is-error': fieldError(bodyField(body, 'velocity.x')) }">
                <label>vx</label><input v-model="body.velocityX" />
                <small v-if="fieldError(bodyField(body, 'velocity.x'))" class="field-error">{{ fieldError(bodyField(body, 'velocity.x')) }}</small>
              </div>
              <div class="field" :class="{ 'is-error': fieldError(bodyField(body, 'velocity.y')) }">
                <label>vy</label><input v-model="body.velocityY" />
                <small v-if="fieldError(bodyField(body, 'velocity.y'))" class="field-error">{{ fieldError(bodyField(body, 'velocity.y')) }}</small>
              </div>
              <div class="field" :class="{ 'is-error': fieldError(bodyField(body, 'velocity.z')) }">
                <label>vz</label><input v-model="body.velocityZ" />
                <small v-if="fieldError(bodyField(body, 'velocity.z'))" class="field-error">{{ fieldError(bodyField(body, 'velocity.z')) }}</small>
              </div>
            </div>
          </template>
        </template>
      </div>
    </div>

    <div class="parameter-section">
      <header><span>结束条件</span></header>
      <div class="field">
        <select v-model="draft.endCondition">
          <option value="MAX_STEPS">最大步数</option>
          <option value="TARGET_TIME">目标模拟时间</option>
          <option value="BOTH">两者满足其一</option>
        </select>
      </div>
      <template v-if="draft.endCondition === 'MAX_STEPS' || draft.endCondition === 'BOTH'">
        <div class="field" :class="{ 'is-error': fieldError('maxSteps') }">
          <AppTooltip :title="'最大步数'" :text="`${help.maxSteps.meaning}${help.maxSteps.impact}${help.maxSteps.range}`">
            <label for="max-steps">最大步数</label>
          </AppTooltip>
          <input id="max-steps" v-model="draft.maxSteps" type="text" inputmode="numeric" />
          <ParameterHelpBlock :help="help.maxSteps" />
          <small v-if="fieldError('maxSteps')" class="field-error">{{ fieldError('maxSteps') }}</small>
          <small v-for="(message, i) in fieldWarnings('maxSteps')" :key="`w-${i}`" class="field-warning">{{ message }}</small>
        </div>
      </template>
      <template v-if="draft.endCondition === 'TARGET_TIME' || draft.endCondition === 'BOTH'">
        <div class="field" :class="{ 'is-error': fieldError('targetSimulationTimeSeconds') }">
          <AppTooltip :title="'目标模拟时间'" :text="`${help.totalTime.meaning}${help.totalTime.impact}${help.totalTime.range}`">
            <label for="target-time">目标模拟时间</label>
          </AppTooltip>
          <input id="target-time" v-model="draft.targetSimulationTime" />
          <small class="param-unit">{{ unitLabel('time', unitSystem) }}</small>
          <ParameterHelpBlock :help="help.totalTime" />
          <small v-if="fieldError('targetSimulationTimeSeconds')" class="field-error">{{ fieldError('targetSimulationTimeSeconds') }}</small>
          <small v-for="(message, i) in fieldWarnings('targetSimulationTimeSeconds')" :key="`w-${i}`" class="field-warning">{{ message }}</small>
        </div>
      </template>
    </div>

    <div class="parameter-section">
      <header><span>显示设置</span></header>
      <label class="check-row"><input type="checkbox" :checked="true" disabled />轨迹线<span>2,000 点</span></label>
    </div>

    <section v-if="configSummary" class="config-summary" aria-label="当前配置解读">
      <header><b>当前配置意味着</b><span>服务端按当前参数计算</span></header>
      <dl>
        <div><dt>预计积分步数</dt><dd>{{ configSummary.estimatedSteps?.toLocaleString() ?? '—' }}</dd></div>
        <div><dt>模拟覆盖时间</dt><dd>{{ configSummary.estimatedSimulationTimeSeconds == null ? '—' : formatSimulationTime(configSummary.estimatedSimulationTimeSeconds) }}</dd></div>
        <div><dt>预计结束条件</dt><dd>{{ configSummary.limitingEndCondition === 'MAX_STEPS' ? '先达到最大步数' : configSummary.limitingEndCondition === 'TARGET_TIME' ? '先达到目标时间' : '两个条件同时达到' }}</dd></div>
        <div><dt>初始最近天体对</dt><dd>{{ bodyNames(configSummary.initialMinimumPairBodyIds) || '—' }}</dd></div>
        <div><dt>初始最近距离</dt><dd>{{ configSummary.initialMinimumPairDistanceMeters == null ? '—' : `${formatScientific(fromSi(configSummary.initialMinimumPairDistanceMeters, 'length', unitSystem))} ${unitLabel('length', unitSystem)}` }}</dd></div>
        <div><dt>软化 / 最近距离</dt><dd>{{ configSummary.softeningToInitialDistanceRatio == null ? '—' : formatScientific(configSummary.softeningToInitialDistanceRatio) }}</dd></div>
      </dl>
    </section>

    <div class="validation-summary">
      <div v-if="draftStore.validationError" class="validation-item is-error">{{ draftStore.validationError }}</div>
      <div v-if="draftStore.guidanceApplyError" class="validation-item is-warning">{{ draftStore.guidanceApplyError }}</div>
      <div v-if="draftStore.pendingGuidancePlan" class="guidance-limit-actions">
        <button type="button" @click="draftStore.resolveGuidanceStepLimit('SHORTEN_DURATION')">保留建议步长，缩短覆盖时间</button>
        <button type="button" @click="draftStore.resolveGuidanceStepLimit('PRESERVE_DURATION')">保持覆盖时间，使用最小合法步长</button>
        <button type="button" class="secondary" @click="draftStore.cancelGuidanceStepLimit()">取消，手动修改</button>
      </div>
      <div v-for="(item, index) in draftStore.allIssues" :key="index" class="validation-item" :class="item.severity === 'ERROR' ? 'is-error' : 'is-warning'">
        <span v-if="item.severity === 'WARNING'" class="risk-badge">{{ item.riskLevel === 'HIGH' ? '高风险' : '注意' }}</span>
        <span v-if="item.severity === 'ERROR' || !item.guidance">{{ item.message }}</span>
      </div>
    </div>

    <button
      class="apply-button"
      :disabled="!draftStore.canSubmit || draftStore.validating || draftStore.creating"
      @click="$emit('apply')"
    >
      {{ draftStore.creating ? '正在创建…' : draftStore.validating ? '校验中…' : '应用参数并开始实验' }}
    </button>
  </div>
</template>
