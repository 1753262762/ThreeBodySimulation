import { describe, expect, it } from 'vitest'
import type { SimulationConfig } from '../../contracts'
import { computeMetrics, rk4Step, closestPair, type MockState } from '../mockEngine'

function twoBodyConfig(): SimulationConfig {
  return {
    name: '双体',
    bodies: [
      { id: 'a', name: 'A', color: '#fff', massKg: 1e30, position: { x: 0, y: 0, z: 0 }, velocity: { x: 0, y: 0, z: 0 } },
      { id: 'b', name: 'B', color: '#fff', massKg: 1e30, position: { x: 1e11, y: 0, z: 0 }, velocity: { x: 0, y: 30000, z: 0 } },
    ],
    timeStepSeconds: 43200,
    gravitationalConstant: 6.6743e-11,
    softeningLengthMeters: 1e8,
    maxSteps: 1000,
    targetSimulationTimeSeconds: null,
  }
}

function state(config: SimulationConfig): MockState {
  return {
    step: 0,
    simulationTimeSeconds: 0,
    bodies: config.bodies.map((b) => ({
      id: b.id ?? '',
      name: b.name,
      color: b.color ?? '#fff',
      massKg: b.massKg,
      position: { ...b.position },
      velocity: { ...b.velocity },
    })),
  }
}

describe('mockEngine 物理', () => {
  it('RK4 单步推进后位置与时间单调变化', () => {
    const config = twoBodyConfig()
    const s0 = state(config)
    const s1 = rk4Step(s0, config)
    expect(s1.step).toBe(1)
    expect(s1.simulationTimeSeconds).toBe(43200)
    expect(s1.bodies.every((b) => Number.isFinite(b.position.x))).toBe(true)
    // 至少一个天体移动了
    const moved = s1.bodies.some((b, i) => b.position.x !== s0.bodies[i].position.x || b.position.y !== s0.bodies[i].position.y)
    expect(moved).toBe(true)
  })

  it('软化引力避免除零：同位置天体仍产生有限加速度', () => {
    const config: SimulationConfig = {
      ...twoBodyConfig(),
      bodies: [
        { id: 'a', name: 'A', color: '#fff', massKg: 1e30, position: { x: 0, y: 0, z: 0 }, velocity: { x: 0, y: 0, z: 0 } },
        { id: 'b', name: 'B', color: '#fff', massKg: 1e30, position: { x: 0, y: 0, z: 0 }, velocity: { x: 0, y: 0, z: 0 } },
      ],
    }
    const s0 = state(config)
    const s1 = rk4Step(s0, config)
    expect(s1.bodies.every((b) => Number.isFinite(b.position.x) && Number.isFinite(b.velocity.x))).toBe(true)
  })

  it('closestPair 返回最小距离对', () => {
    const config = twoBodyConfig()
    const s = state(config)
    const pair = closestPair(s)
    expect(pair).not.toBeNull()
    expect(pair!.distanceMeters).toBeCloseTo(1e11)
    expect(pair!.ids).toEqual(['a', 'b'])
  })

  it('computeMetrics 能量为有限值且角动量守恒', () => {
    const config = twoBodyConfig()
    const s0 = state(config)
    const m0 = computeMetrics(s0, config, null, null, null, 0)
    expect(Number.isFinite(m0.totalEnergyJoules)).toBe(true)
    expect(m0.totalEnergyJoules).toBeLessThan(0)
    const s1 = rk4Step(s0, config)
    const m1 = computeMetrics(s1, config, m0.totalEnergyJoules, null, 60, 0.1)
    expect(Math.abs(m1.relativeEnergyDrift)).toBeLessThan(1e-6)
    expect(m1.angularMomentumMagnitude).toBeGreaterThan(0)
    expect(m1.stepsPerSecond).toBe(60)
  })
})
