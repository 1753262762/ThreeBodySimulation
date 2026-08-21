import { describe, expect, it } from 'vitest'
import type { SimulationState } from '../../contracts'
import { PlaybackBuffer } from '../playbackBuffer'

const BODY_IDS = ['a', 'b']

function makeState(step: number, time: number, posA: [number, number, number], posB: [number, number, number]): SimulationState {
  return {
    step,
    simulationTimeSeconds: time,
    bodies: [
      { id: 'a', position: { x: posA[0], y: posA[1], z: posA[2] }, velocity: { x: 1, y: 1, z: 1 } },
      { id: 'b', position: { x: posB[0], y: posB[1], z: posB[2] }, velocity: { x: -1, y: -1, z: -1 } },
    ],
  }
}

describe('PlaybackBuffer 构造', () => {
  it('空 body 列表抛出错误', () => {
    expect(() => new PlaybackBuffer([])).toThrow()
  })
})

describe('PlaybackBuffer 层与优先级', () => {
  it('live 层有序追加并按容量裁剪', () => {
    const buffer = new PlaybackBuffer(BODY_IDS, { liveCapacity: 3 })
    buffer.appendLive([
      makeState(10, 1, [0, 0, 0], [5, 0, 0]),
      makeState(20, 2, [1, 0, 0], [6, 0, 0]),
      makeState(30, 3, [2, 0, 0], [7, 0, 0]),
      makeState(40, 4, [3, 0, 0], [8, 0, 0]),
    ])
    expect(buffer.layerCount('live')).toBe(3)
    expect(buffer.findAtStep(10)).toBeNull()
    const at30 = buffer.findAtStep(30)
    expect(at30?.layer).toBe('live')
    expect(at30?.state.step).toBe(30)
    const states = buffer.queryRange(0, 100)
    expect(states.map((s) => s.step)).toEqual([20, 30, 40])
  })

  it('相同 step 高优先级层覆盖低优先级层', () => {
    const buffer = new PlaybackBuffer(BODY_IDS)
    buffer.replaceOverview([makeState(10, 1, [0, 0, 0], [5, 0, 0])])
    buffer.appendLive([makeState(10, 1, [9, 9, 9], [5, 0, 0])])
    const found = buffer.findAtStep(10)
    expect(found?.layer).toBe('live')
    expect(found?.state.bodies[0]?.position.x).toBe(9)
  })

  it('exact 层优先于 focus 与 live', () => {
    const buffer = new PlaybackBuffer(BODY_IDS)
    buffer.appendLive([makeState(50, 5, [1, 1, 1], [5, 0, 0])])
    buffer.replaceFocus([makeState(50, 5, [2, 2, 2], [5, 0, 0])])
    buffer.setExact(makeState(50, 5, [7, 7, 7], [5, 0, 0]))
    const found = buffer.findAtStep(50)
    expect(found?.layer).toBe('exact')
    expect(found?.state.bodies[0]?.position.x).toBe(7)
  })

  it('setExact 只保留最近一次精确定位', () => {
    const buffer = new PlaybackBuffer(BODY_IDS)
    buffer.setExact(makeState(1, 0.1, [0, 0, 0], [1, 0, 0]))
    buffer.setExact(makeState(2, 0.2, [1, 0, 0], [2, 0, 0]))
    expect(buffer.layerCount('exact')).toBe(1)
    expect(buffer.findAtStep(1)).toBeNull()
    expect(buffer.findAtStep(2)?.layer).toBe('exact')
  })

  it('重复 step 插入去重', () => {
    const buffer = new PlaybackBuffer(BODY_IDS, { liveCapacity: 4 })
    buffer.appendLive([
      makeState(10, 1, [0, 0, 0], [5, 0, 0]),
      makeState(20, 2, [1, 0, 0], [6, 0, 0]),
      makeState(20, 2, [9, 9, 9], [6, 0, 0]),
      makeState(30, 3, [2, 0, 0], [7, 0, 0]),
    ])
    expect(buffer.layerCount('live')).toBe(3)
    expect(buffer.findAtStep(20)?.state.bodies[0]?.position.x).toBe(1)
  })

  it('乱序插入仍保持升序', () => {
    const buffer = new PlaybackBuffer(BODY_IDS, { liveCapacity: 10 })
    buffer.appendLive([
      makeState(30, 3, [2, 0, 0], [7, 0, 0]),
      makeState(10, 1, [0, 0, 0], [5, 0, 0]),
      makeState(20, 2, [1, 0, 0], [6, 0, 0]),
    ])
    expect(buffer.queryRange(0, 100).map((s) => s.step)).toEqual([10, 20, 30])
  })

  it('overview 替换超出容量时保留最新点', () => {
    const buffer = new PlaybackBuffer(BODY_IDS, { overviewCapacity: 3 })
    buffer.replaceOverview([
      makeState(1, 0.1, [0, 0, 0], [1, 0, 0]),
      makeState(2, 0.2, [1, 0, 0], [2, 0, 0]),
      makeState(3, 0.3, [2, 0, 0], [3, 0, 0]),
      makeState(4, 0.4, [3, 0, 0], [4, 0, 0]),
    ])
    expect(buffer.queryRange(0, 100).map((s) => s.step)).toEqual([2, 3, 4])
  })
})

describe('PlaybackBuffer 查找与插值', () => {
  it('findNearest 返回最近 step', () => {
    const buffer = new PlaybackBuffer(BODY_IDS)
    buffer.replaceOverview([
      makeState(10, 1, [0, 0, 0], [5, 0, 0]),
      makeState(50, 5, [4, 0, 0], [9, 0, 0]),
    ])
    const nearest = buffer.findNearest(48)
    expect(nearest?.state.step).toBe(50)
    expect(nearest?.delta).toBe(2)
  })

  it('interpolateAt 命中精确点时不标近似', () => {
    const buffer = new PlaybackBuffer(BODY_IDS)
    buffer.setExact(makeState(10, 1, [0, 0, 0], [5, 0, 0]))
    const point = buffer.interpolateAt(10)
    expect(point.approximate).toBe(false)
    expect(point.step).toBe(10)
  })

  it('interpolateAt 在两个缓存点之间线性插值', () => {
    const buffer = new PlaybackBuffer(BODY_IDS)
    buffer.appendLive([
      makeState(10, 1, [0, 0, 0], [5, 0, 0]),
      makeState(20, 3, [2, 0, 0], [7, 0, 0]),
    ])
    const point = buffer.interpolateAt(15)
    expect(point.approximate).toBe(true)
    expect(point.simulationTimeSeconds).toBeCloseTo(2, 6)
    expect(point.bodies[0]?.position.x).toBeCloseTo(1, 6)
    expect(point.bodies[1]?.position.x).toBeCloseTo(6, 6)
  })

  it('interpolateAt 跨层选择全局最近包围点而不是较远的高优先级点', () => {
    const buffer = new PlaybackBuffer(BODY_IDS)
    buffer.setExact(makeState(10, 1, [1_000, 0, 0], [5, 0, 0]))
    buffer.replaceOverview([makeState(80, 8, [80, 0, 0], [85, 0, 0])])
    buffer.appendLive([makeState(95, 9.5, [95, 0, 0], [100, 0, 0])])
    buffer.replaceFocus([makeState(120, 12, [120, 0, 0], [125, 0, 0])])

    const point = buffer.interpolateAt(100)

    expect(point.bodies[0]?.position.x).toBeCloseTo(100, 6)
    expect(point.simulationTimeSeconds).toBeCloseTo(10, 6)
  })

  it('interpolateAt 只有单侧缓存时退化为最近值', () => {
    const buffer = new PlaybackBuffer(BODY_IDS)
    buffer.replaceOverview([makeState(10, 1, [0, 0, 0], [5, 0, 0])])
    const below = buffer.interpolateAt(5)
    expect(below.approximate).toBe(true)
    expect(below.bodies[0]?.position.x).toBe(0)
    const above = buffer.interpolateAt(15)
    expect(above.approximate).toBe(true)
    expect(above.bodies[0]?.position.x).toBe(0)
  })

  it('interpolateAt 空缓存返回空近似点', () => {
    const buffer = new PlaybackBuffer(BODY_IDS)
    const point = buffer.interpolateAt(5)
    expect(point.approximate).toBe(true)
    expect(point.bodies).toEqual([])
  })

  it('queryRange 闭区间且按优先级去重', () => {
    const buffer = new PlaybackBuffer(BODY_IDS)
    buffer.replaceOverview([
      makeState(10, 1, [0, 0, 0], [5, 0, 0]),
      makeState(20, 2, [1, 0, 0], [6, 0, 0]),
    ])
    buffer.replaceFocus([makeState(20, 2, [9, 9, 9], [6, 0, 0])])
    const range = buffer.queryRange(20, 100)
    expect(range.length).toBe(1)
    expect(range[0]?.bodies[0]?.position.x).toBe(9)
  })

  it('clear 清空所有层', () => {
    const buffer = new PlaybackBuffer(BODY_IDS)
    buffer.replaceOverview([makeState(10, 1, [0, 0, 0], [5, 0, 0])])
    buffer.setExact(makeState(10, 1, [0, 0, 0], [5, 0, 0]))
    buffer.clear()
    expect(buffer.layerCount('overview')).toBe(0)
    expect(buffer.layerCount('exact')).toBe(0)
    expect(buffer.queryRange(0, 100)).toEqual([])
  })
})
