<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

/**
 * ECharts 折线图：统一封装能量、角动量、间距三类时序。
 * 单位全部为 SI，与契约一致。
 */
const props = defineProps<{
  title: string
  subtitle: string
  series: { name: string; unit: string; color: string; data: [number, number][] }[]
  height?: number
  /** Charts are hidden in the expanded scene and should not consume updates. */
  paused?: boolean
}>()

const chartRef = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

function buildOption(): echarts.EChartsCoreOption {
  return {
    backgroundColor: 'transparent',
    grid: { top: 28, right: 14, bottom: 26, left: 52 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(12, 22, 38, 0.95)',
      borderColor: '#22324a',
      textStyle: { color: '#dfe9f5', fontSize: 11 },
    },
    legend: {
      top: 4,
      right: 12,
      textStyle: { color: '#8fa3bd', fontSize: 11 },
      icon: 'roundRect',
    },
    xAxis: {
      type: 'value',
      axisLabel: { color: '#6d829e', fontSize: 10 },
      axisLine: { lineStyle: { color: '#22324a' } },
      splitLine: { lineStyle: { color: 'rgba(34, 50, 74, 0.4)' } },
      name: 'step',
      nameTextStyle: { color: '#6d829e', fontSize: 10 },
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: '#6d829e', fontSize: 10 },
      axisLine: { lineStyle: { color: '#22324a' } },
      splitLine: { lineStyle: { color: 'rgba(34, 50, 74, 0.4)' } },
      scale: true,
    },
    series: props.series.map((s) => ({
      name: s.name,
      type: 'line',
      data: s.data,
      smooth: false,
      symbol: 'none',
      lineStyle: { color: s.color, width: 1.5 },
      areaStyle: {
        color: {
          type: 'linear',
          x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: s.color + '33' },
            { offset: 1, color: s.color + '00' },
          ],
        },
      },
      animation: false,
    })),
  }
}

let ro: ResizeObserver | null = null
let updateTimer: ReturnType<typeof setTimeout> | null = null
let lastUpdateAt = 0
const UPDATE_INTERVAL_MS = 500

function updateChart(): void {
  updateTimer = null
  if (!chart || props.paused || (typeof document !== 'undefined' && document.hidden)) return
  chart.setOption(buildOption(), { replaceMerge: ['series'] })
  lastUpdateAt = Date.now()
}

function scheduleUpdate(): void {
  if (props.paused || (typeof document !== 'undefined' && document.hidden)) return
  const elapsed = Date.now() - lastUpdateAt
  if (elapsed >= UPDATE_INTERVAL_MS) {
    updateChart()
    return
  }
  if (updateTimer !== null) return
  updateTimer = setTimeout(updateChart, UPDATE_INTERVAL_MS - Math.max(0, elapsed))
}

function onVisibilityChange(): void {
  if (typeof document === 'undefined' || !document.hidden) scheduleUpdate()
}

function mount(): void {
  if (!chartRef.value) return
  chart = echarts.init(chartRef.value, undefined, { renderer: 'canvas' })
  chart.setOption(buildOption())
  lastUpdateAt = Date.now()
  ro = new ResizeObserver(() => chart?.resize())
  ro.observe(chartRef.value)
}

watch(
  () => props.series,
  scheduleUpdate,
  { deep: true },
)

watch(
  () => props.paused,
  (paused) => {
    if (!paused) scheduleUpdate()
  },
)

onMounted(() => {
  mount()
  document.addEventListener('visibilitychange', onVisibilityChange)
})
onBeforeUnmount(() => {
  if (updateTimer !== null) clearTimeout(updateTimer)
  chart?.dispose()
  chart = null
  ro?.disconnect()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <article class="chart-card">
    <header>
      <b>{{ title }}</b>
      <span>{{ subtitle }}</span>
    </header>
    <div ref="chartRef" class="chart-body" :style="{ minHeight: (height ?? 180) + 'px' }"></div>
  </article>
</template>
