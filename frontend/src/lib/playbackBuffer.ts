/**
 * PlaybackBuffer：历史回看的只读缓存。
 *
 * 与只保留相邻两帧的 SnapshotBuffer 不同，本缓冲按固定 body ID 顺序保存
 * 历史 step、时间、位置与速度，分为四个有界层：
 *   exact   最近一次精确定位结果，1 点
 *   focus   最近一次历史范围请求，最多 2,000 点
 *   live    WS TRAJECTORY 最近 8,000 点
 *   overview 全可用范围概览，最多 2,000 点
 * 查找优先级 exact → focus → live → overview，相同 step 按该优先级覆盖。
 *
 * 本模块只做缓存与仅供显示的插值，不写回权威 Store；近似结果一律以
 * `approximate` 标记，调用方不得把它当作精确状态。
 */
import type { BodyState, SimulationState, Vector3 } from '../contracts'

export type PlaybackLayer = 'overview' | 'live' | 'focus' | 'exact'

export const PLAYBACK_LAYER_PRIORITY: readonly PlaybackLayer[] = ['exact', 'focus', 'live', 'overview']

export interface PlaybackBufferOptions {
  overviewCapacity?: number
  liveCapacity?: number
  focusCapacity?: number
  exactCapacity?: number
}

export const DEFAULT_PLAYBACK_CAPACITIES = {
  overviewCapacity: 2000,
  liveCapacity: 8000,
  focusCapacity: 2000,
  exactCapacity: 1,
} satisfies Required<PlaybackBufferOptions>

export interface PlaybackState {
  step: number
  simulationTimeSeconds: number
  bodies: BodyState[]
}

export interface InterpolatedPoint extends PlaybackState {
  /** 当前画面来自相邻缓存点的最近值或线性插值时为 true。 */
  approximate: boolean
}

/** 单个层的环形数据：step 数组按升序保存，位置/速度按 body 顺序连续存放。 */
class LayerBuffer {
  readonly layer: PlaybackLayer
  readonly capacity: number
  readonly bodyCount: number
  count = 0
  private steps: Float64Array
  private times: Float64Array
  private positions: Float64Array
  private velocities: Float64Array

  constructor(layer: PlaybackLayer, capacity: number, bodyCount: number) {
    this.layer = layer
    this.capacity = Math.max(1, Math.floor(capacity))
    this.bodyCount = bodyCount
    this.steps = new Float64Array(this.capacity)
    this.times = new Float64Array(this.capacity)
    this.positions = new Float64Array(this.capacity * bodyCount * 3)
    this.velocities = new Float64Array(this.capacity * bodyCount * 3)
  }

  private pointStride(): number {
    return this.bodyCount * 3
  }

  private pointIndex(index: number): number {
    return index * this.pointStride()
  }

  clear(): void {
    this.count = 0
  }

  stepAt(index: number): number {
    return this.steps[index] as number
  }

  timeAt(index: number): number {
    return this.times[index] as number
  }

  /** 二分查找 step 的插入位置，返回第一个 >= step 的下标。 */
  private lowerBound(step: number): number {
    let low = 0
    let high = this.count
    while (low < high) {
      const mid = (low + high) >>> 1
      if ((this.steps[mid] as number) < step) low = mid + 1
      else high = mid
    }
    return low
  }

  findExact(step: number): number {
    const index = this.lowerBound(step)
    if (index < this.count && (this.steps[index] as number) === step) return index
    return -1
  }

  /** 返回最后一个 step <= target 的下标，不存在返回 -1。 */
  findFloor(step: number): number {
    const index = this.lowerBound(step)
    if (index < this.count && (this.steps[index] as number) === step) return index
    return index - 1
  }

  /** 返回第一个 step >= target 的下标，不存在返回 -1。 */
  findCeil(step: number): number {
    const index = this.lowerBound(step)
    return index < this.count ? index : -1
  }

  private insertAt(index: number, state: SimulationState, bodyOrder: readonly string[]): void {
    if (this.count >= this.capacity) {
      this.shiftLeft(1)
      this.insertAt(Math.max(0, index - 1), state, bodyOrder)
      return
    }
    const end = this.count
    this.steps.copyWithin(index + 1, index, end)
    this.times.copyWithin(index + 1, index, end)
    this.positions.copyWithin(this.pointIndex(index + 1), this.pointIndex(index), this.pointIndex(end))
    this.velocities.copyWithin(this.pointIndex(index + 1), this.pointIndex(index), this.pointIndex(end))
    this.writePoint(index, state, bodyOrder)
    this.count += 1
  }

  /** 把数组整体左移 removeCount 个点，保留尾部的 count-removeCount 个点。 */
  private shiftLeft(removeCount: number): void {
    if (removeCount <= 0 || removeCount >= this.count) {
      this.clear()
      return
    }
    this.steps.copyWithin(0, removeCount, this.count)
    this.times.copyWithin(0, removeCount, this.count)
    this.positions.copyWithin(0, this.pointIndex(removeCount), this.pointIndex(this.count))
    this.velocities.copyWithin(0, this.pointIndex(removeCount), this.pointIndex(this.count))
    this.count -= removeCount
  }

  private writePoint(index: number, state: SimulationState, bodyOrder: readonly string[]): void {
    this.steps[index] = state.step
    this.times[index] = state.simulationTimeSeconds
    const base = this.pointIndex(index)
    for (let b = 0; b < this.bodyCount; b += 1) {
      const body = state.bodies.find((item) => item.id === bodyOrder[b])
      const offset = base + b * 3
      if (body) {
        this.positions[offset] = body.position.x
        this.positions[offset + 1] = body.position.y
        this.positions[offset + 2] = body.position.z
        this.velocities[offset] = body.velocity.x
        this.velocities[offset + 1] = body.velocity.y
        this.velocities[offset + 2] = body.velocity.z
      } else {
        this.positions[offset] = 0
        this.positions[offset + 1] = 0
        this.positions[offset + 2] = 0
        this.velocities[offset] = 0
        this.velocities[offset + 1] = 0
        this.velocities[offset + 2] = 0
      }
    }
  }

  /** 按 step 去重插入；重复 step 保留已有值。 */
  insert(state: SimulationState, bodyOrder: readonly string[]): void {
    if (this.findExact(state.step) >= 0) return
    this.insertAt(this.lowerBound(state.step), state, bodyOrder)
  }

  /** 整体替换为一批点（调用方保证升序）；超出容量时保留最新 capacity 个。 */
  replace(states: SimulationState[], bodyOrder: readonly string[]): void {
    this.clear()
    for (const state of states) {
      if (this.findExact(state.step) >= 0) continue
      if (this.count >= this.capacity) this.shiftLeft(1)
      this.insertAt(this.lowerBound(state.step), state, bodyOrder)
    }
  }

  /** 读取第 index 个点，按 bodyOrder 重建 BodyState 数组。 */
  read(index: number, bodyOrder: readonly string[]): BodyState[] {
    const base = this.pointIndex(index)
    const bodies: BodyState[] = []
    for (let b = 0; b < this.bodyCount; b += 1) {
      const offset = base + b * 3
      const position: Vector3 = {
        x: this.positions[offset] as number,
        y: this.positions[offset + 1] as number,
        z: this.positions[offset + 2] as number,
      }
      const velocity: Vector3 = {
        x: this.velocities[offset] as number,
        y: this.velocities[offset + 1] as number,
        z: this.velocities[offset + 2] as number,
      }
      bodies.push({ id: bodyOrder[b] as string, position, velocity })
    }
    return bodies
  }
}

export class PlaybackBuffer {
  readonly bodyIds: readonly string[]
  private readonly layers: Record<PlaybackLayer, LayerBuffer>

  constructor(bodyIds: readonly string[], options: PlaybackBufferOptions = {}) {
    if (bodyIds.length === 0) throw new Error('PlaybackBuffer 需要至少一个 body id')
    const capacities = { ...DEFAULT_PLAYBACK_CAPACITIES, ...options }
    this.bodyIds = [...bodyIds]
    const bodyCount = this.bodyIds.length
    this.layers = {
      overview: new LayerBuffer('overview', capacities.overviewCapacity, bodyCount),
      live: new LayerBuffer('live', capacities.liveCapacity, bodyCount),
      focus: new LayerBuffer('focus', capacities.focusCapacity, bodyCount),
      exact: new LayerBuffer('exact', capacities.exactCapacity, bodyCount),
    }
  }

  private layer(layer: PlaybackLayer): LayerBuffer {
    return this.layers[layer]
  }

  private readState(layer: PlaybackLayer, index: number): PlaybackState | null {
    if (index < 0 || index >= this.layer(layer).count) return null
    return {
      step: this.layer(layer).stepAt(index),
      simulationTimeSeconds: this.layer(layer).timeAt(index),
      bodies: this.layer(layer).read(index, this.bodyIds),
    }
  }

  replaceOverview(states: SimulationState[]): void {
    this.layers.overview.replace(states, this.bodyIds)
  }

  replaceFocus(states: SimulationState[]): void {
    this.layers.focus.replace(states, this.bodyIds)
  }

  appendLive(states: SimulationState[]): void {
    for (const state of states) this.layers.live.insert(state, this.bodyIds)
  }

  setExact(state: SimulationState): void {
    this.layers.exact.clear()
    this.layers.exact.insert(state, this.bodyIds)
  }

  clear(): void {
    for (const layer of PLAYBACK_LAYER_PRIORITY) this.layer(layer).clear()
  }

  /** 按优先级在四层中精确查找指定 step。 */
  findAtStep(step: number): { layer: PlaybackLayer; state: PlaybackState } | null {
    for (const layerName of PLAYBACK_LAYER_PRIORITY) {
      const layer = this.layer(layerName)
      const index = layer.findExact(step)
      if (index >= 0) return { layer: layerName, state: this.readState(layerName, index) as PlaybackState }
    }
    return null
  }

  /** 全层最近 step 查找；距离相同时按优先级取更上层。 */
  findNearest(step: number): { layer: PlaybackLayer; state: PlaybackState; delta: number } | null {
    let best: { layer: PlaybackLayer; state: PlaybackState; delta: number } | null = null
    for (const layerName of PLAYBACK_LAYER_PRIORITY) {
      const layer = this.layer(layerName)
      if (layer.count === 0) continue
      const floor = layer.findFloor(step)
      const candidates = [floor, floor + 1 < layer.count ? floor + 1 : -1]
      for (const index of candidates) {
        if (index < 0) continue
        const state = this.readState(layerName, index) as PlaybackState
        const delta = Math.abs(state.step - step)
        if (!best || delta < best.delta) best = { layer: layerName, state, delta }
      }
    }
    return best
  }

  /**
   * 在目标步处给出显示点：
   * 命中精确点直接返回；否则用上下两个缓存点做线性插值，
   * 只有一侧存在时退化为最近值。结果均带 approximate 标记。
   */
  interpolateAt(step: number): InterpolatedPoint {
    const exact = this.findAtStep(step)
    if (exact) return { ...exact.state, approximate: false }

    let floor: PlaybackState | null = null
    let ceil: PlaybackState | null = null
    for (const layerName of PLAYBACK_LAYER_PRIORITY) {
      const layer = this.layer(layerName)
      if (layer.count === 0) continue
      if (!floor) {
        const floorIndex = layer.findFloor(step)
        if (floorIndex >= 0) floor = this.readState(layerName, floorIndex) as PlaybackState
      }
      if (!ceil) {
        const ceilIndex = layer.findCeil(step)
        if (ceilIndex >= 0) ceil = this.readState(layerName, ceilIndex) as PlaybackState
      }
      if (floor && ceil) break
    }

    if (floor && ceil && floor.step !== ceil.step) {
      const t = (step - floor.step) / (ceil.step - floor.step)
      const bodies: BodyState[] = floor.bodies.map((body, index) => {
        const next = (ceil as PlaybackState).bodies[index] as BodyState
        return {
          id: body.id,
          position: {
            x: lerp(body.position.x, next.position.x, t),
            y: lerp(body.position.y, next.position.y, t),
            z: lerp(body.position.z, next.position.z, t),
          },
          velocity: {
            x: lerp(body.velocity.x, next.velocity.x, t),
            y: lerp(body.velocity.y, next.velocity.y, t),
            z: lerp(body.velocity.z, next.velocity.z, t),
          },
        }
      })
      return {
        step,
        simulationTimeSeconds: lerp(floor.simulationTimeSeconds, ceil.simulationTimeSeconds, t),
        bodies,
        approximate: true,
      }
    }

    const nearest = floor ?? ceil
    if (nearest) return { ...nearest, step, approximate: true }
    return { step, simulationTimeSeconds: 0, bodies: [], approximate: true }
  }

  /** 闭区间范围查询，按 step 升序、跨层去重（高层优先）。 */
  queryRange(fromStep: number, toStep: number): PlaybackState[] {
    const seen = new Map<number, PlaybackState>()
    for (const layerName of PLAYBACK_LAYER_PRIORITY) {
      const layer = this.layer(layerName)
      if (layer.count === 0) continue
      const startIndex = layer.findCeil(fromStep)
      if (startIndex < 0) continue
      for (let i = startIndex; i < layer.count; i += 1) {
        const step = layer.stepAt(i)
        if (step > toStep) break
        if (seen.has(step)) continue
        const state = this.readState(layerName, i)
        if (state) seen.set(step, state)
      }
    }
    return [...seen.values()].sort((a, b) => a.step - b.step)
  }

  layerCount(layer: PlaybackLayer): number {
    return this.layer(layer).count
  }
}

function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t
}
