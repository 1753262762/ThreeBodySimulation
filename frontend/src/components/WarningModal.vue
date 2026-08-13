<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import type { GuidanceAction, ValidationIssue } from '../contracts'
import ValidationGuidanceCard from './ValidationGuidanceCard.vue'

const props = defineProps<{
  open: boolean
  warnings: ValidationIssue[]
}>()

const emit = defineEmits<{
  (e: 'confirm'): void
  (e: 'cancel'): void
  (e: 'apply', action: GuidanceAction): void
}>()

const dialogRef = ref<HTMLElement | null>(null)
/** 打开前聚焦的元素，关闭后恢复焦点。 */
let lastFocused: HTMLElement | null = null

const RISK_LABELS: Record<string, string> = {
  CAUTION: '注意',
  HIGH: '高风险',
}

function focusDialog(): void {
  nextTick(() => {
    const dialog = dialogRef.value
    if (!dialog) return
    const first = dialog.querySelector<HTMLElement>('[data-autofocus]')
    ;(first ?? dialog).focus()
  })
}

function onKeydown(event: KeyboardEvent): void {
  if (!props.open) return
  if (event.key === 'Escape') {
    event.preventDefault()
    emit('cancel')
    return
  }
  if (event.key !== 'Tab') return
  const dialog = dialogRef.value
  if (!dialog) return
  const focusables = Array.from(
    dialog.querySelectorAll<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
    ),
  )
  if (focusables.length === 0) return
  const first = focusables[0] as HTMLElement
  const last = focusables[focusables.length - 1] as HTMLElement
  const active = document.activeElement
  if (event.shiftKey) {
    if (active === first || !dialog.contains(active)) {
      event.preventDefault()
      last.focus()
    }
  } else if (active === last || !dialog.contains(active)) {
    event.preventDefault()
    first.focus()
  }
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      lastFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
      focusDialog()
    } else if (lastFocused) {
      lastFocused.focus()
      lastFocused = null
    }
  },
)
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="modal-backdrop" @click.self="emit('cancel')">
      <div
        ref="dialogRef"
        class="warning-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="warning-modal-title"
        aria-describedby="warning-modal-desc"
        tabindex="-1"
        @keydown="onKeydown"
      >
        <h2 id="warning-modal-title">存在运行风险</h2>
        <p id="warning-modal-desc" class="modal-hint">先查看证据、收益和代价。你可以应用建议后再运行，也可以保留原配置研究不稳定或非束缚系统。</p>
        <ul class="warning-list">
          <li
            v-for="(warning, index) in warnings"
            :key="index"
            class="warning-item"
            :class="`risk-${(warning.riskLevel ?? 'CAUTION').toLowerCase()}`"
          >
            <ValidationGuidanceCard
              v-if="warning.guidance"
              :issue="warning"
              @apply="emit('apply', $event)"
            />
            <template v-else>
              <span class="risk-badge">{{ RISK_LABELS[warning.riskLevel ?? 'CAUTION'] }}</span>
              <code class="warning-field">{{ warning.field }}</code>
              <span class="warning-message">{{ warning.message }}</span>
            </template>
          </li>
        </ul>
        <div class="modal-actions">
          <button type="button" class="secondary" data-autofocus @click="emit('cancel')">
            返回修改
          </button>
          <button type="button" class="danger" @click="emit('confirm')">理解风险，仍按原配置运行</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
