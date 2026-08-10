<script setup lang="ts">
import { onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import LabView from './LabView.vue'
import { useExperimentsStore } from '../stores/experiments'

const route = useRoute()
const experimentsStore = useExperimentsStore()
const experimentId = () => String(route.params.id)

async function load(): Promise<void> {
  const id = experimentId()
  if (!id) return
  try {
    await experimentsStore.loadExperiment(id)
    experimentsStore.connect(id)
  } catch {
    // 错误已记录到 store。
  }
}

onMounted(() => {
  void load()
})

onUnmounted(() => {
  experimentsStore.disconnect()
})

watch(() => route.params.id, () => {
  experimentsStore.disconnect()
  void load()
})
</script>

<template>
  <LabView />
</template>
