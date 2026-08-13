<script setup lang="ts">
import { computed } from 'vue'
import type { SimulationEvent, SimulationHealthReport } from '../contracts'
import { formatSimulationTime } from '../lib/format'

const props = defineProps<{
  events: SimulationEvent[]
  health: SimulationHealthReport | null
}>()

const observations = computed(() => props.events
  .filter((event) => event.type === 'NEAR_ENCOUNTER' || event.type === 'DIAGNOSTIC')
  .map((event) => ({
    key: event.eventId ?? String(event.sequence),
    text: event.type === 'NEAR_ENCOUNTER'
      ? `在 ${formatSimulationTime(event.simulationTimeSeconds)} 观察到天体进入近遇阈值。近遇本身不等于碰撞或计算错误。`
      : `在 ${formatSimulationTime(event.simulationTimeSeconds)} 观察到：${event.diagnostic?.summary ?? event.message}`,
  })))

const hypotheses = computed(() => props.events
  .flatMap((event) => event.diagnostic?.likelyCauses ?? [])
  .filter((item, index, list) => list.indexOf(item) === index))

const interpretationLimit = computed(() => props.health != null && props.health.status !== 'GOOD')
</script>

<template>
  <section class="phenomenon-overview" aria-label="现象总览">
    <header><b>现象总览</b><span>把观察事实与原因推测分开</span></header>
    <div class="phenomenon-columns">
      <article>
        <h3>已观察到</h3>
        <p v-if="observations.length === 0">目前没有近遇、逃逸倾向、剧烈偏转或快速解体记录。</p>
        <ul v-else><li v-for="item in observations" :key="item.key">{{ item.text }}</li></ul>
      </article>
      <article>
        <h3>可能相关</h3>
        <p v-if="hypotheses.length === 0">现有数据不足以提出具体解释。</p>
        <ul v-else><li v-for="item in hypotheses" :key="item">{{ item }}（待对照实验验证）</li></ul>
        <p v-if="interpretationLimit" class="interpretation-limit">当前数值误差限制了物理解读，不能把轨迹变化直接当作真实物理结论。</p>
        <p v-else-if="health?.status === 'GOOD'">这些现象在项目当前数值容差内出现，但这不等于模型已经代表真实宇宙。</p>
      </article>
      <article>
        <h3>如何确认</h3>
        <p>先定位到相关事件时间，再创建时间步长更小的对照实验。若现象在数值健康改善后仍出现，才能更有把握地把它解释为当前模型中的物理行为。</p>
      </article>
    </div>
  </section>
</template>
