import { describe, expect, it } from 'vitest'
import type { Metrics, SimulationConfig } from '../../contracts'
import { angularMomentumHealth, conservationLevel } from '../conservation'

const config: SimulationConfig = {
  name: '角动量测试',
  timeStepSeconds: 1,
  gravitationalConstant: 1,
  softeningLengthMeters: 0.1,
  maxSteps: 10,
  targetSimulationTimeSeconds: null,
  bodies: [
    {
      id: 'a', name: 'A', color: '#ffffff', massKg: 2,
      position: { x: 1, y: 0, z: 0 }, velocity: { x: 0, y: 1, z: 0 },
    },
  ],
}

function metrics(x: number, y: number, z: number): Metrics {
  return {
    kineticEnergyJoules: 0,
    potentialEnergyJoules: 0,
    totalEnergyJoules: 0,
    initialTotalEnergyJoules: 0,
    relativeEnergyDrift: 0,
    angularMomentum: { x, y, z },
    angularMomentumMagnitude: Math.hypot(x, y, z),
    linearMomentum: { x: 0, y: 0, z: 0 },
    linearMomentumMagnitude: 0,
    minimumPairDistanceMeters: 0,
  }
}

describe('conservationLevel', () => {
  it('按漂移绝对值映射正负方向和不可用状态', () => {
    expect(conservationLevel(-0.001)).toBe('NOTICE')
    expect(conservationLevel(-0.01)).toBe('WARNING')
    expect(conservationLevel(-0.05)).toBe('CRITICAL')
    expect(conservationLevel(Number.NaN)).toBe('UNAVAILABLE')
  })
})

describe('angularMomentumHealth', () => {
  it('从初始配置计算基准并识别稳定状态', () => {
    const health = angularMomentumHealth(config, metrics(0, 0, 2))
    expect(health?.initial).toEqual({ x: 0, y: 0, z: 2 })
    expect(health?.relativeDrift).toBe(0)
    expect(health?.level).toBe('STABLE')
  })

  it.each([
    [0.001, 'NOTICE'],
    [0.01, 'WARNING'],
    [0.05, 'CRITICAL'],
  ] as const)('按漂移阈值 %s 映射为 %s', (drift, level) => {
    expect(angularMomentumHealth(config, metrics(drift * 2, 0, 2))?.level).toBe(level)
  })

  it('同模长但方向变化仍识别为漂移', () => {
    const health = angularMomentumHealth(config, metrics(2, 0, 0))
    expect(health?.initialMagnitude).toBe(2)
    expect(health?.relativeDrift).toBeCloseTo(Math.SQRT2)
    expect(health?.level).toBe('CRITICAL')
  })

  it('净角动量接近零时使用贡献模长和作为参考', () => {
    const balanced: SimulationConfig = {
      ...config,
      bodies: [
        config.bodies[0],
        {
          id: 'b', name: 'B', color: '#000000', massKg: 2,
          position: { x: -1, y: 0, z: 0 }, velocity: { x: 0, y: 1, z: 0 },
        },
      ],
    }
    const health = angularMomentumHealth(balanced, metrics(0, 0, 0.04))
    expect(health?.initialMagnitude).toBe(0)
    expect(health?.relativeDrift).toBeCloseTo(0.01)
    expect(health?.level).toBe('WARNING')
  })
})
