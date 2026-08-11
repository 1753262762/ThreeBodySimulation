import { describe, expect, it } from 'vitest'
import { BodyTrajectoryBuffer, TrajectoryBuffer } from '../trajectoryBuffer'

describe('BodyTrajectoryBuffer', () => {
  it('defaults to the 60 Hz two-minute live window', () => {
    expect(new BodyTrajectoryBuffer().capacity).toBe(8000)
    expect(new TrajectoryBuffer().capacity).toBe(8000)
  })

  it('8000点满载后只覆盖最旧真实样本', () => {
    const buffer = new BodyTrajectoryBuffer()
    for (let step = 1; step <= 8001; step += 1) buffer.append(step, step, 0, 0)
    expect(buffer.pointCount).toBe(8000)
    expect(buffer.earliestStep).toBe(2)
    expect(buffer.latestStep).toBe(8001)
  })

  it('按 step 去重并在容量满后环形覆盖最旧点', () => {
    const buffer = new BodyTrajectoryBuffer(3)
    expect(buffer.append(1, 1, 0, 0)).toBe(true)
    expect(buffer.append(2, 2, 0, 0)).toBe(true)
    expect(buffer.append(2, 200, 0, 0)).toBe(false)
    expect(buffer.append(1, 100, 0, 0)).toBe(false)
    expect(buffer.append(3, 3, 0, 0)).toBe(true)
    expect(buffer.append(4, 4, 0, 0)).toBe(true)
    expect(buffer.pointCount).toBe(3)
    expect(buffer.acceptedSteps).toEqual([2, 3, 4])

    const points: number[] = []
    buffer.forEachProjected('XY', (point) => points.push(point.x, point.y))
    expect(points).toEqual([2, 0, 3, 0, 4, 0])
    expect(buffer.toFloat64Array()).toEqual(new Float64Array([2, 0, 0, 3, 0, 0, 4, 0, 0]))
  })

  it('reset 后允许从新序列开始', () => {
    const buffer = new BodyTrajectoryBuffer(2)
    buffer.append(10, 10, 0, 0)
    buffer.clear()
    expect(buffer.append(1, 1, 0, 0)).toBe(true)
    expect(buffer.acceptedSteps).toEqual([1])
  })
})

describe('TrajectoryBuffer', () => {
  it('按体分桶并提供高效迭代入口', () => {
    const trails = new TrajectoryBuffer(2)
    trails.append('a', 1, { x: 1, y: 2, z: 3 })
    trails.append('a', 2, { x: 2, y: 3, z: 4 })
    trails.append('a', 3, { x: 3, y: 4, z: 5 })
    trails.append('b', 1, { x: -1, y: -2, z: -3 })
    expect(trails.size).toBe(2)
    expect(trails.get('a')?.acceptedSteps).toEqual([2, 3])
    const seen: string[] = []
    trails.forEachBody((id, body) => seen.push(`${id}:${body.pointCount}`))
    expect(seen).toEqual(['a:2', 'b:1'])
  })
})
