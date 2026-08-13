<script setup lang="ts">
import { computed } from 'vue'
import type { Metrics, SimulationConfig } from '../contracts'
import { angularMomentumHealth, conservationLevel, type ConservationLevel } from '../lib/conservation'
import { formatPercent, formatScientific, formatSimulationTime } from '../lib/format'

const props = defineProps<{
  metrics: Metrics | null
  config: SimulationConfig | null
  step: number
  simulationTimeSeconds: number
}>()

const angularHealth = computed(() => angularMomentumHealth(props.config, props.metrics))
const energyLevel = computed(() => props.metrics
  ? conservationLevel(props.metrics.relativeEnergyDrift)
  : 'UNAVAILABLE')

const LEVEL_LABELS: Record<ConservationLevel, string> = {
  STABLE: '稳定',
  NOTICE: '提示',
  WARNING: '警告',
  CRITICAL: '严重',
  UNAVAILABLE: '无法归一化',
}

const angularStatusText = computed(() => {
  const health = angularHealth.value
  if (!health) return '—'
  if (health.level === 'UNAVAILABLE') return LEVEL_LABELS[health.level]
  return `${formatPercent(health.relativeDrift, 3)} 漂移 · ${LEVEL_LABELS[health.level]}`
})

const energyStatusText = computed(() => props.metrics
  ? `${formatPercent(props.metrics.relativeEnergyDrift, 4)} 漂移 · ${LEVEL_LABELS[energyLevel.value]}`
  : '—')
</script>

<template>
  <div class="lab-kpis">
    <article class="total-energy-kpi" :class="`health-${energyLevel.toLowerCase()}`">
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
      <em :data-level="energyLevel">{{ energyStatusText }}</em>
    </article>
    <article
      class="angular-momentum-kpi"
      :class="angularHealth ? `health-${angularHealth.level.toLowerCase()}` : ''"
    >
      <span class="kpi-heading">
        角动量
        <small
          v-if="angularHealth"
          class="kpi-reference"
          :title="`初始角动量 ${formatScientific(angularHealth.initialMagnitude)} kg·m²/s`"
        >
          初始 {{ formatScientific(angularHealth.initialMagnitude) }}
        </small>
      </span>
      <b>{{ metrics ? formatScientific(metrics.angularMomentumMagnitude) + ' kg·m²/s' : '—' }}</b>
      <em :data-level="angularHealth?.level ?? 'UNAVAILABLE'">
        {{ angularStatusText }}
      </em>
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
