import { describe, expect, it } from 'vitest'
import type { SimulationConfig } from '../../contracts'
import {
  MAX_ALLOWED_STEPS,
  planComparisonExperiment,
  resolveStepLimit,
} from '../comparisonExperiment'

const base: SimulationConfig = {
  name: '对照规划',
  bodies: [
    { id: 'a', name: '甲', massKg: 1, position: { x: -1, y: 0, z: 0 }, velocity: { x: 0, y: 0, z: 0 } },
    { id: 'b', name: '乙', massKg: 1, position: { x: 1, y: 0, z: 0 }, velocity: { x: 0, y: 0, z: 0 } },
  ],
  timeStepSeconds: 10,
  gravitationalConstant: 1,
  softeningLengthMeters: 0.01,
  maxSteps: 1_000,
  targetSimulationTimeSeconds: null,
}

describe('对照实验规划', () => {
  it('MAX_STEPS 按时间步长比例扩展最大步数并保持模拟时长', () => {
    const plan = planComparisonExperiment(base, 1)
    expect(plan.proposedMaxSteps).toBe(10_000)
    expect(plan.proposedSimulationTimeSeconds).toBe(plan.originalSimulationTimeSeconds)
    expect(plan.relativeStepCount).toBe(10)
  })

  it('TARGET_TIME 保持目标时间不变', () => {
    const plan = planComparisonExperiment({
      ...base,
      maxSteps: null,
      targetSimulationTimeSeconds: 10_000,
    }, 2)
    expect(plan.proposedConfig.targetSimulationTimeSeconds).toBe(10_000)
    expect(plan.proposedEstimatedSteps).toBe(5_000)
  })

  it('BOTH 同时缩放最大步数并保留目标时间', () => {
    const plan = planComparisonExperiment({ ...base, targetSimulationTimeSeconds: 8_000 }, 2)
    expect(plan.proposedMaxSteps).toBe(5_000)
    expect(plan.proposedConfig.targetSimulationTimeSeconds).toBe(8_000)
    expect(plan.proposedSimulationTimeSeconds).toBe(8_000)
  })

  it('超过一亿步时不静默截断，并支持两个明确解决策略', () => {
    const source = { ...base, maxSteps: 20_000_000 }
    const plan = planComparisonExperiment(source, 1)
    expect(plan.exceedsStepLimit).toBe(true)

    const shortened = resolveStepLimit(plan, 'SHORTEN_DURATION')
    expect(shortened.exceedsStepLimit).toBe(false)
    expect(shortened.proposedMaxSteps).toBe(MAX_ALLOWED_STEPS)

    const preserved = resolveStepLimit(plan, 'PRESERVE_DURATION')
    expect(preserved.exceedsStepLimit).toBe(false)
    expect(preserved.proposedSimulationTimeSeconds).toBe(plan.originalSimulationTimeSeconds)
  })
})
