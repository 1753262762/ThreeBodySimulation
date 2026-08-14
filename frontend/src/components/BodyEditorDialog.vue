<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { ValidationIssue } from '../contracts'
import { draftToConfig, type BodyDraft, type ConfigDraft } from '../lib/configDraft'
import { getParameterHelp } from '../lib/parameterHelp'
import { unitLabel, type UnitSystem } from '../lib/units'
import AppTooltip from './AppTooltip.vue'
import ParameterHelpBlock from './ParameterHelpBlock.vue'

const props = defineProps<{
  open: boolean
  mode: 'add' | 'edit'
  body: BodyDraft | null
  configDraft: ConfigDraft
  unitSystem: UnitSystem
}>()

const emit = defineEmits<{
  (e: 'save', body: BodyDraft): void
  (e: 'close'): void
}>()

const dialogRef = ref<HTMLElement | null>(null)
const confirmRef = ref<HTMLElement | null>(null)
const nameInputRef = ref<HTMLInputElement | null>(null)
const cancelButtonRef = ref<HTMLButtonElement | null>(null)
const tempBody = ref<BodyDraft | null>(null)
const initialFingerprint = ref('')
const discardConfirmationOpen = ref(false)
let lastFocused: HTMLElement | null = null

const titleId = `body-editor-title-${Math.random().toString(36).slice(2, 10)}`
const discardTitleId = `body-editor-discard-title-${Math.random().toString(36).slice(2, 10)}`

const help = computed(() => ({
  mass: getParameterHelp('mass', props.unitSystem),
  position: getParameterHelp('position', props.unitSystem),
  velocity: getParameterHelp('velocity', props.unitSystem),
}))

const bodyIndex = computed(() => {
  if (props.mode === 'add') return props.configDraft.bodies.length
  const rowId = tempBody.value?.rowId
  return Math.max(0, props.configDraft.bodies.findIndex((body) => body.rowId === rowId))
})

function issueKey(issue: ValidationIssue): string {
  return `${issue.code}|${issue.field}|${issue.message}`
}

const baselineIssueKeys = computed(() => new Set(
  draftToConfig(props.configDraft, props.unitSystem).issues.map(issueKey),
))

const currentIssues = computed<ValidationIssue[]>(() => {
  const body = tempBody.value
  if (!body) return []
  const bodies = props.configDraft.bodies.map((item) => ({ ...item }))
  if (props.mode === 'add') bodies.push({ ...body })
  else bodies.splice(bodyIndex.value, 1, { ...body })
  const issues = draftToConfig({ ...props.configDraft, bodies }, props.unitSystem).issues
  const prefix = `bodies[${bodyIndex.value}]`
  return issues.flatMap((issue) => {
    if (issue.field === prefix || issue.field.startsWith(`${prefix}.`)) return [issue]
    if (issue.code === 'COINCIDENT_BODIES' && !baselineIssueKeys.value.has(issueKey(issue))) {
      return [{ ...issue, field: `${prefix}.position` }]
    }
    if (issue.field === 'bodies') return [issue]
    return []
  })
})

const hasErrors = computed(() => currentIssues.value.some((issue) => issue.severity === 'ERROR'))
const dirty = computed(() => tempBody.value != null && JSON.stringify(tempBody.value) !== initialFingerprint.value)

function fieldError(suffix: string): string | undefined {
  const field = `bodies[${bodyIndex.value}].${suffix}`
  return currentIssues.value.find((issue) => issue.field === field && issue.severity === 'ERROR')?.message
}

function groupError(suffix: string): string | undefined {
  const prefix = `bodies[${bodyIndex.value}].${suffix}`
  return currentIssues.value.find((issue) => (
    issue.severity === 'ERROR'
    && (issue.field === prefix || issue.field.startsWith(`${prefix}.`))
  ))?.message
}

function focusFirst(): void {
  void nextTick(() => nameInputRef.value?.focus())
}

function trapFocus(event: KeyboardEvent, root: HTMLElement | null): void {
  if (event.key !== 'Tab' || !root) return
  const focusableSelector = 'button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])'
  const focusables = Array.from(root.querySelectorAll<HTMLElement>('*'))
    .filter((element) => element.matches(focusableSelector))
  if (focusables.length === 0) return
  const first = focusables[0] as HTMLElement
  const last = focusables[focusables.length - 1] as HTMLElement
  const active = document.activeElement
  if (event.shiftKey) {
    if (active === first || !root.contains(active)) {
      event.preventDefault()
      last.focus()
    }
  } else if (active === last || !root.contains(active)) {
    event.preventDefault()
    first.focus()
  }
}

function finalizeClose(): void {
  discardConfirmationOpen.value = false
  emit('close')
}

function requestClose(): void {
  if (dirty.value) {
    discardConfirmationOpen.value = true
    void nextTick(() => confirmRef.value?.querySelector<HTMLElement>('[data-autofocus]')?.focus())
  } else {
    finalizeClose()
  }
}

function continueEditing(): void {
  discardConfirmationOpen.value = false
  void nextTick(() => cancelButtonRef.value?.focus())
}

function save(): void {
  if (!tempBody.value || hasErrors.value) return
  emit('save', { ...tempBody.value })
}

function onDialogKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    requestClose()
    return
  }
  trapFocus(event, dialogRef.value)
}

function onConfirmKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    continueEditing()
    return
  }
  trapFocus(event, confirmRef.value)
}

watch(
  () => props.open,
  (open) => {
    if (open && props.body) {
      lastFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
      tempBody.value = { ...props.body }
      initialFingerprint.value = JSON.stringify(tempBody.value)
      discardConfirmationOpen.value = false
      focusFirst()
    } else if (!open) {
      tempBody.value = null
      discardConfirmationOpen.value = false
      if (lastFocused) {
        const focusTarget = lastFocused
        void nextTick(() => focusTarget.focus())
        lastFocused = null
      }
    }
  },
  { immediate: true },
)
</script>

<template>
  <Teleport to="body">
    <div v-if="open && tempBody" class="modal-backdrop" @click.self="requestClose">
      <section
        ref="dialogRef"
        class="warning-modal body-editor-dialog"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        tabindex="-1"
        @keydown="onDialogKeydown"
      >
        <header class="body-editor-dialog-header">
          <div>
            <h2 :id="titleId">{{ mode === 'add' ? '添加天体' : `编辑天体 · ${body?.name || '未命名'}` }}</h2>
            <p class="modal-hint">所有修改将在保存后写入实验参数。</p>
          </div>
          <button type="button" class="body-editor-close" aria-label="关闭天体编辑" @click="requestClose">×</button>
        </header>

        <div class="body-editor-dialog-content">
          <section class="body-editor-section">
            <h3>基本信息</h3>
            <div class="body-editor-header">
              <label class="body-color-field">
                <span>颜色</span>
                <input v-model="tempBody.color" type="color" aria-label="天体颜色" />
                <small v-if="fieldError('color')" class="field-error">{{ fieldError('color') }}</small>
              </label>
              <div class="field" :class="{ 'is-error': fieldError('name') }">
                <label for="body-editor-name">名称</label>
                <input id="body-editor-name" ref="nameInputRef" v-model="tempBody.name" type="text" />
                <small v-if="fieldError('name')" class="field-error">{{ fieldError('name') }}</small>
              </div>
            </div>
            <div class="field" :class="{ 'is-error': fieldError('massKg') }">
              <AppTooltip title="天体质量" :text="`${help.mass.meaning}${help.mass.impact}${help.mass.range}`">
                <label for="body-editor-mass">质量</label>
              </AppTooltip>
              <div class="input-with-unit">
                <input id="body-editor-mass" v-model="tempBody.mass" aria-describedby="body-editor-mass-unit" />
                <small id="body-editor-mass-unit" class="input-unit">{{ unitLabel('mass', unitSystem) }}</small>
              </div>
              <ParameterHelpBlock title="天体质量" :help="help.mass" />
              <small v-if="fieldError('massKg')" class="field-error">{{ fieldError('massKg') }}</small>
            </div>
          </section>

          <section class="body-editor-section">
            <div class="param-group-title">
              <AppTooltip title="初始位置" :text="`${help.position.meaning}${help.position.impact}${help.position.range}`">
                <h3>初始位置</h3>
              </AppTooltip>
              <small class="param-unit">{{ unitLabel('length', unitSystem) }}</small>
            </div>
            <ParameterHelpBlock title="初始位置" :help="help.position" />
            <small v-if="groupError('position')" class="field-error">{{ groupError('position') }}</small>
            <div class="coord-grid">
              <div class="field" :class="{ 'is-error': fieldError('position.x') }">
                <label for="body-editor-position-x">x</label>
                <input id="body-editor-position-x" v-model="tempBody.positionX" />
                <small v-if="fieldError('position.x')" class="field-error">{{ fieldError('position.x') }}</small>
              </div>
              <div class="field" :class="{ 'is-error': fieldError('position.y') }">
                <label for="body-editor-position-y">y</label>
                <input id="body-editor-position-y" v-model="tempBody.positionY" />
                <small v-if="fieldError('position.y')" class="field-error">{{ fieldError('position.y') }}</small>
              </div>
              <div class="field" :class="{ 'is-error': fieldError('position.z') }">
                <label for="body-editor-position-z">z</label>
                <input id="body-editor-position-z" v-model="tempBody.positionZ" />
                <small v-if="fieldError('position.z')" class="field-error">{{ fieldError('position.z') }}</small>
              </div>
            </div>
          </section>

          <section class="body-editor-section">
            <div class="param-group-title">
              <AppTooltip title="初始速度" :text="`${help.velocity.meaning}${help.velocity.impact}${help.velocity.range}`">
                <h3>初始速度</h3>
              </AppTooltip>
              <small class="param-unit">{{ unitLabel('velocity', unitSystem) }}</small>
            </div>
            <ParameterHelpBlock title="初始速度" :help="help.velocity" />
            <small v-if="groupError('velocity')" class="field-error">{{ groupError('velocity') }}</small>
            <div class="coord-grid">
              <div class="field" :class="{ 'is-error': fieldError('velocity.x') }">
                <label for="body-editor-velocity-x">vx</label>
                <input id="body-editor-velocity-x" v-model="tempBody.velocityX" />
                <small v-if="fieldError('velocity.x')" class="field-error">{{ fieldError('velocity.x') }}</small>
              </div>
              <div class="field" :class="{ 'is-error': fieldError('velocity.y') }">
                <label for="body-editor-velocity-y">vy</label>
                <input id="body-editor-velocity-y" v-model="tempBody.velocityY" />
                <small v-if="fieldError('velocity.y')" class="field-error">{{ fieldError('velocity.y') }}</small>
              </div>
              <div class="field" :class="{ 'is-error': fieldError('velocity.z') }">
                <label for="body-editor-velocity-z">vz</label>
                <input id="body-editor-velocity-z" v-model="tempBody.velocityZ" />
                <small v-if="fieldError('velocity.z')" class="field-error">{{ fieldError('velocity.z') }}</small>
              </div>
            </div>
          </section>
        </div>

        <footer class="modal-actions body-editor-dialog-actions">
          <button ref="cancelButtonRef" type="button" class="secondary" @click="requestClose">取消</button>
          <button type="button" :disabled="hasErrors" @click="save">保存</button>
        </footer>
      </section>
    </div>
  </Teleport>

  <Teleport to="body">
    <div v-if="discardConfirmationOpen" class="modal-backdrop body-editor-confirm-backdrop" @click.self="continueEditing">
      <section
        ref="confirmRef"
        class="warning-modal body-editor-confirm"
        role="alertdialog"
        aria-modal="true"
        :aria-labelledby="discardTitleId"
        tabindex="-1"
        @keydown="onConfirmKeydown"
      >
        <h2 :id="discardTitleId">放弃未保存的修改？</h2>
        <p class="modal-hint">关闭弹窗将丢弃本次对天体参数的所有修改。</p>
        <div class="modal-actions">
          <button type="button" class="secondary" data-autofocus @click="continueEditing">继续编辑</button>
          <button type="button" class="danger" @click="finalizeClose">放弃修改</button>
        </div>
      </section>
    </div>
  </Teleport>
</template>
