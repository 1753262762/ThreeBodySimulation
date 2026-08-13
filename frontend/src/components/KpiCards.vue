<script setup lang="ts">
import type { Metrics, SimulationHealthReport } from '../contracts'
import { formatScientific } from '../lib/format'
import SimulationHealthCard from './SimulationHealthCard.vue'

defineProps<{
  metrics: Metrics | null
  health?: SimulationHealthReport | null
  reviewing?: boolean
}>()
const emit = defineEmits<{ cloneRetry: [suggestedTimeStepSeconds: number] }>()
</script>

<template>
  <div class="lab-kpis">
    <article class="total-energy-kpi">
      <span class="kpi-heading">
        总能量
        <small
          v-if="metrics"
          class="kpi-reference"
          :title="`初始总能量 ${formatScientific(metrics.initialTotalEnergyJoules)} J`"
        >
          初始 {{ formatScientific(metrics.initialTotalEnergyJoules) }}
        </small>
      </span>
      <b>{{ metrics ? formatScientific(metrics.totalEnergyJoules) + ' J' : '—' }}</b>
      <em>瞬时指标</em>
    </article>
    <article
      class="angular-momentum-kpi"
    >
      <span class="kpi-heading">
        角动量
      </span>
      <b>{{ metrics ? formatScientific(metrics.angularMomentumMagnitude) + ' kg·m²/s' : '—' }}</b>
      <em>瞬时指标</em>
    </article>
    <article>
      <span>最近天体间距</span>
      <b>{{ metrics ? formatScientific(metrics.minimumPairDistanceMeters) + ' m' : '—' }}</b>
      <em>实时</em>
    </article>
    <SimulationHealthCard :health="health ?? null" :reviewing="reviewing" @clone-retry="emit('cloneRetry', $event)" />
  </div>
</template>
