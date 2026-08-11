import { describe, expect, it } from 'vitest'
import type { SimulationState } from '../../contracts'
import { SnapshotBuffer, interpolateSnapshots } from '../snapshotBuffer'

function state(step: number, time: number, x: number, velocity = 1): SimulationState {
  return {
    step,
    simulationTimeSeconds: time,
    bodies: [{
      id: 'a',
      position: { x, y: 0, z: 0 },
      velocity: { x: velocity, y: 0, z: 0 },
    }],
  }
}

describe('SnapshotBuffer', () => {
  it('保留前后帧并在到达时间区间内插值', () => {
    const buffer = new SnapshotBuffer({ interpolationDelayMs: 0 })
    const first = state(1, 0, 0)
    const second = state(2, 10, 10)
    buffer.push(first, 100)
    buffer.push(second, 200)

    const halfway = buffer.readInterpolated(150)
    expect(halfway?.bodies[0].position.x).toBeCloseTo(5)
    expect(buffer.previous?.state.step).toBe(1)
    expect(buffer.current?.state.step).toBe(2)
    // The display result is detached from both authoritative frames.
    if (halfway) halfway.bodies[0].position.x = 999
    expect(first.bodies[0].position.x).toBe(0)
    expect(second.bodies[0].position.x).toBe(10)
  })

  it('约束 Hermite，不越过最新快照，也安全处理缺失体', () => {
    const previous = state(1, 0, 0, 100)
    const current = state(2, 1, 10, -100)
    const interpolated = interpolateSnapshots(previous, current, 0.5)
    expect(interpolated.bodies[0].position.x).toBeGreaterThanOrEqual(0)
    expect(interpolated.bodies[0].position.x).toBeLessThanOrEqual(10)

    const missing = interpolateSnapshots(
      { ...previous, bodies: [] },
      current,
      0.5,
    )
    expect(missing.bodies[0].position.x).toBe(10)
  })

  it('时间落后/超前时分别回退到前帧/当前帧', () => {
    const buffer = new SnapshotBuffer({ interpolationDelayMs: 0 })
    buffer.push(state(1, 0, 1), 100)
    buffer.push(state(2, 1, 2), 200)
    expect(buffer.readInterpolated(0)?.bodies[0].position.x).toBe(1)
    expect(buffer.readInterpolated(999)?.bodies[0].position.x).toBe(2)
  })

  it('同一步重采样替换当前帧，旧步骤不会倒退', () => {
    const buffer = new SnapshotBuffer({ interpolationDelayMs: 0 })
    buffer.push(state(2, 2, 2), 200)
    buffer.push(state(1, 1, 1), 300)
    expect(buffer.current?.state.step).toBe(2)
    buffer.push(state(2, 2, 3), 400)
    expect(buffer.current?.state.bodies[0].position.x).toBe(3)
  })

  it('60Hz 连续到达时默认使用一帧延迟，显示位置在相邻到达之间前进', () => {
    const buffer = new SnapshotBuffer()
    const interval = 1000 / 60
    buffer.push(state(0, 0, 0), 0)
    buffer.push(state(1, 1, 1), interval)
    const firstHalf = buffer.readInterpolated(interval + interval / 2)
    expect(buffer.observedArrivalIntervalMs).toBeCloseTo(interval)
    expect(firstHalf?.bodies[0].position.x).toBeGreaterThan(0)
    expect(firstHalf?.bodies[0].position.x).toBeLessThan(1)

    buffer.push(state(2, 2, 2), interval * 2)
    const secondHalf = buffer.readInterpolated(interval * 2 + interval / 2)
    expect(secondHalf?.bodies[0].position.x).toBeGreaterThan(1)
    expect(secondHalf?.bodies[0].position.x).toBeLessThan(2)
  })

  it('插值追上当前帧后不再要求 rAF，收到新帧后重新挂起', () => {
    const buffer = new SnapshotBuffer()
    buffer.push(state(0, 0, 0), 0)
    buffer.push(state(1, 1, 1), 16)
    expect(buffer.hasPendingInterpolation(20)).toBe(true)
    expect(buffer.hasPendingInterpolation(40)).toBe(false)
    buffer.push(state(2, 2, 2), 32)
    expect(buffer.hasPendingInterpolation(33)).toBe(true)
  })
})
