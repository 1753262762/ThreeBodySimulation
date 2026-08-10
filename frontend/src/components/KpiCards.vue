<script setup lang="ts">
import type { Metrics } from '../contracts'
import { formatPercent, formatScientific, formatSimulationTime } from '../lib/format'

defineProps<{
  metrics: Metrics | null
  step: number
  simulationTimeSeconds: number
}>()
</script>

<template>
  <div class="lab-kpis">
    <article>
      <span>总能量</span>
      <b>{{ metrics ? formatScientific(metrics.totalEnergyJoules) + ' J' : '—' }}</b>
      <em>{{ metrics ? formatPercent(metrics.relativeEnergyDrift, 4) + ' 漂移' : '—' }}</em>
    </article>
    <article>
      <span>角动量</span>
      <b>{{ metrics ? formatScientific(metrics.angularMomentumMagnitude) + ' kg·m²/s' : '—' }}</b>
      <em>守恒</em>
    </article>
    <article>
      <span>最近天体间距</span>
      <b>{{ metrics ? formatScientific(metrics.minimumPairDistanceMeters) + ' m' : '—' }}</b>
      <em>实时</em>
    </article>
    <article>
      <span>模拟进度</span>
      <b>{{ formatSimulationTime(simulationTimeSeconds) }}</b>
      <em>{{ step.toLocaleString() }} 步</em>
    </article>
  </div>
</template>
