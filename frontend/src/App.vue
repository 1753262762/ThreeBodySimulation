<script setup lang="ts">
import { onMounted } from 'vue'
import AppHeader from './components/AppHeader.vue'
import { useDraftStore } from './stores/draft'
import { useExperimentsStore } from './stores/experiments'

const draftStore = useDraftStore()
const experimentsStore = useExperimentsStore()

onMounted(() => {
  void draftStore.loadPresets()
  void experimentsStore.loadList()
})
</script>

<template>
  <div class="app-shell">
    <AppHeader />
    <RouterView />
  </div>
</template>