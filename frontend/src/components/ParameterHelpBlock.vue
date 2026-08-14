<script setup lang="ts">
import { nextTick, ref } from 'vue'
import type { ParameterHelp } from '../lib/parameterHelp'

defineProps<{
  title: string
  help: ParameterHelp
}>()

const open = ref(false)
const triggerRef = ref<HTMLButtonElement | null>(null)
const dialogRef = ref<HTMLElement | null>(null)
const titleId = `parameter-help-title-${Math.random().toString(36).slice(2, 10)}`
const descriptionId = `parameter-help-description-${Math.random().toString(36).slice(2, 10)}`

function openDialog(): void {
  open.value = true
  void nextTick(() => dialogRef.value?.querySelector<HTMLElement>('[data-autofocus]')?.focus())
}

function closeDialog(): void {
  open.value = false
  void nextTick(() => triggerRef.value?.focus())
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeDialog()
    return
  }
  if (event.key === 'Tab') {
    event.preventDefault()
    dialogRef.value?.querySelector<HTMLElement>('[data-autofocus]')?.focus()
  }
}
</script>

<template>
  <button ref="triggerRef" type="button" class="parameter-help-button" @click="openDialog">
    作用、风险与调整方法
  </button>
  <Teleport to="body">
    <div v-if="open" class="modal-backdrop" @click.self="closeDialog">
      <section
        ref="dialogRef"
        class="warning-modal parameter-help-modal"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        :aria-describedby="descriptionId"
        tabindex="-1"
        @keydown="onKeydown"
      >
        <h2 :id="titleId">{{ title }}</h2>
        <p :id="descriptionId" class="modal-hint">{{ help.meaning }}</p>
        <div class="parameter-help-sections">
          <section>
            <h3>作用与风险</h3>
            <p>{{ help.impact }}</p>
          </section>
          <section>
            <h3>调整方法</h3>
            <p>{{ help.range }}</p>
          </section>
        </div>
        <div class="modal-actions">
          <button type="button" class="secondary" data-autofocus @click="closeDialog">关闭</button>
        </div>
      </section>
    </div>
  </Teleport>
</template>
