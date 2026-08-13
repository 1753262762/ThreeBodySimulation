<script setup lang="ts">
import type { ParameterHelp } from '../lib/parameterHelp'

defineProps<{
  help: ParameterHelp
}>()
</script>

<template>
  <div class="parameter-help">
    <p>{{ help.meaning }}</p>
    <details>
      <summary>作用、风险与调整方法</summary>
      <p>{{ help.impact }}</p>
      <p>{{ help.range }}</p>
    </details>
  </div>
</template>
