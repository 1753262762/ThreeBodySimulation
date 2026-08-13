<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { usePreferencesStore } from '../stores/preferences'
import { getChartPalette } from '../lib/theme'

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

/**
 * ECharts 折线图：统一封装能量、角动量、间距三类时序。
 * 单位全部为 SI，与契约一致。颜色来自语义色板，主题变化时完整刷新。
 */
const props = defineProps<{
  title: string
  subtitle: string
  series: { name: string; unit: string; color: string; data: [number, number][] }[]
  /** Charts are hidden in the expanded scene and should not consume updates. */
  paused?: boolean
}>()

const preferences = usePreferencesStore()
const chartRef = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

function buildOption(): echarts.EChartsCoreOption {
  const palette = getChartPalette(preferences.resolvedTheme)
  return {
    backgroundColor: 'transparent',
    grid: { top: 28, right: 14, bottom: 26, left: 52 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: palette.tooltipBackground,
      borderColor: palette.tooltipBorder,
      textStyle: { color: palette.tooltipText, fontSize: 11 },
    },
    legend: {
      top: 4,
      right: 12,
      textStyle: { color: palette.legendText, fontSize: 11 },
      icon: 'roundRect',
    },
    xAxis: {
      type: 'value',
      axisLabel: { color: palette.axisText, fontSize: 10 },
      axisLine: { lineStyle: { color: palette.axisLine } },
      splitLine: { lineStyle: { color: palette.gridLine } },
      name: 'step',
      nameTextStyle: { color: palette.axisText, fontSize: 10 },
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: palette.axisText, fontSize: 10 },
      axisLine: { lineStyle: { color: palette.axisLine } },
      splitLine: { lineStyle: { color: palette.gridLine } },
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
    if (!paused) {
      requestAnimationFrame(() => {
        chart?.resize()
        scheduleUpdate()
      })
    }
  },
)

// 主题变化时用 notMerge 完整刷新，避免残留旧主题的颜色。
watch(
  () => preferences.resolvedTheme,
  () => {
    if (!chart) return
    chart.setOption(buildOption(), { notMerge: true })
    lastUpdateAt = Date.now()
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
    <div ref="chartRef" class="chart-body"></div>
  </article>
</template>
