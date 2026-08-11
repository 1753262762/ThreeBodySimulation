<script setup lang="ts">
import { computed, ref } from 'vue'
import { useDraftStore } from '../stores/draft'
import { MAX_BODY_COUNT, MIN_BODY_COUNT } from '../contracts'
import { unitLabel } from '../lib/units'

const draftStore = useDraftStore()
const expandedRow = ref<string | null>(null)
const showImport = ref(false)
const importText = ref('')
const importError = ref('')

const unitSystem = computed(() => draftStore.unitSystem)
const draft = computed(() => draftStore.draft)
const issuesByField = computed(() => draftStore.issuesByField)

function fieldError(field: string): string | undefined {
  const list = (issuesByField.value.get(field) ?? []).filter((item) => item.severity === 'ERROR')
  return list[0]?.message
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
      <select id="preset-select" @change="draftStore.applyPreset(($event.target as HTMLSelectElement).value)">
        <option value="" disabled selected>选择内置方案…</option>
        <option v-for="preset in draftStore.presets" :key="preset.key" :value="preset.key">
          {{ preset.key }} · {{ preset.name }}
        </option>
      </select>
    </div>

    <div class="field">
      <label for="exp-name">实验名称</label>
      <input id="exp-name" v-model="draft.name" placeholder="例如：混沌轨道稳定性" />
      <small v-if="fieldError('name')" class="field-error">{{ fieldError('name') }}</small>
    </div>

    <div class="field-row">
      <div class="field" :class="{ 'is-error': fieldError('timeStepSeconds') }">
        <label for="time-step">时间步长</label>
        <input id="time-step" v-model="draft.timeStep" />
        <small>{{ unitLabel('time', unitSystem) }}</small>
        <small v-if="fieldError('timeStepSeconds')" class="field-error">{{ fieldError('timeStepSeconds') }}</small>
      </div>
      <div class="field" :class="{ 'is-error': fieldError('gravitationalConstant') }">
        <label for="grav-const">引力常数</label>
        <input id="grav-const" v-model="draft.gravitationalConstant" />
        <small>N·m²/kg²</small>
        <small v-if="fieldError('gravitationalConstant')" class="field-error">{{ fieldError('gravitationalConstant') }}</small>
      </div>
    </div>

    <div class="field" :class="{ 'is-error': fieldError('softeningLengthMeters') }">
      <label for="softening">软化长度</label>
      <input id="softening" v-model="draft.softeningLength" />
      <small>{{ unitLabel('length', unitSystem) }}</small>
      <small v-if="fieldError('softeningLengthMeters')" class="field-error">{{ fieldError('softeningLengthMeters') }}</small>
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
            <button type="button" :title="expandedRow === body.rowId ? '收起' : '编辑'" @click="toggleExpand(body.rowId)">✎</button>
            <button type="button" title="复制" @click="draftStore.duplicateBody(body.rowId)" :disabled="draft.bodies.length >= MAX_BODY_COUNT">⧉</button>
            <button type="button" class="danger" title="删除" @click="draftStore.removeBody(body.rowId)" :disabled="draft.bodies.length <= MIN_BODY_COUNT">✕</button>
          </div>
        </div>
      </div>

      <div v-if="expandedRow" class="body-editor">
        <template v-for="(body, index) in draft.bodies.filter((item) => item.rowId === expandedRow)" :key="body.rowId">
          <div class="body-editor-header">
            <input type="color" v-model="body.color" />
            <input type="text" v-model="body.name" :placeholder="`天体 ${index + 1} 名称`" />
          </div>
          <div class="coord-grid">
            <div class="field">
              <label>质量</label>
              <input v-model="body.mass" />
              <small>{{ unitLabel('mass', unitSystem) }}</small>
            </div>
          </div>
          <div class="coord-grid">
            <div class="field"><label>x</label><input v-model="body.positionX" /></div>
            <div class="field"><label>y</label><input v-model="body.positionY" /></div>
            <div class="field"><label>z</label><input v-model="body.positionZ" /></div>
          </div>
          <div class="coord-grid">
            <div class="field"><label>vx</label><input v-model="body.velocityX" /></div>
            <div class="field"><label>vy</label><input v-model="body.velocityY" /></div>
            <div class="field"><label>vz</label><input v-model="body.velocityZ" /></div>
          </div>
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
          <label for="max-steps">最大步数</label>
          <input id="max-steps" v-model="draft.maxSteps" type="text" inputmode="numeric" />
          <small v-if="fieldError('maxSteps')" class="field-error">{{ fieldError('maxSteps') }}</small>
        </div>
      </template>
      <template v-if="draft.endCondition === 'TARGET_TIME' || draft.endCondition === 'BOTH'">
        <div class="field" :class="{ 'is-error': fieldError('targetSimulationTimeSeconds') }">
          <label for="target-time">目标模拟时间</label>
          <input id="target-time" v-model="draft.targetSimulationTime" />
          <small>{{ unitLabel('time', unitSystem) }}</small>
          <small v-if="fieldError('targetSimulationTimeSeconds')" class="field-error">{{ fieldError('targetSimulationTimeSeconds') }}</small>
        </div>
      </template>
    </div>

    <div class="parameter-section">
      <header><span>显示设置</span></header>
      <label class="check-row"><input type="checkbox" :checked="true" disabled />轨迹线<span>2,000 点</span></label>
    </div>

    <div class="validation-summary">
      <div v-for="(item, index) in draftStore.allIssues" :key="index" class="validation-item" :class="item.severity === 'ERROR' ? 'is-error' : 'is-warning'">
        {{ item.message }}
      </div>
    </div>

    <button class="apply-button" :disabled="!draftStore.canSubmit || draftStore.validating" @click="$emit('apply')">
      {{ draftStore.validating ? '校验中…' : '应用参数并开始实验' }}
    </button>
  </div>
</template>
