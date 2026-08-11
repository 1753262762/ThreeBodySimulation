import type { Vector3 } from '../contracts'
import type { ProjectionPlane } from '../stores/preferences'

export interface TrajectoryPointInput {
  step: number
  position: Vector3
}

export interface TrajectoryPointView {
  readonly step: number
  readonly x: number
  readonly y: number
  readonly z: number
}

export interface ProjectedPointView {
  readonly step: number
  readonly x: number
  readonly y: number
}

export type TrajectoryPointVisitor = (point: TrajectoryPointView, index: number) => void
export type ProjectedPointVisitor = (point: ProjectedPointView, index: number) => void

/**
 * Fixed-size chronological ring for one body's trajectory. Coordinates live
 * in a Float64Array and steps in a parallel Float64Array; no head splice or
 * per-point object allocation occurs while a simulation is running.
 */
export class BodyTrajectoryBuffer {
  readonly capacity: number
  private readonly coordinates: Float64Array
  private readonly steps: Float64Array
  private head = 0
  private count = 0
  private lastStep = Number.NEGATIVE_INFINITY

  constructor(capacity = 8000) {
    this.capacity = Math.max(1, Math.floor(capacity))
    this.coordinates = new Float64Array(this.capacity * 3)
    this.steps = new Float64Array(this.capacity)
  }

  get pointCount(): number {
    return this.count
  }

  /** Number of scalar coordinates, retained for lightweight legacy checks. */
  get length(): number {
    return this.count * 3
  }

  get latestStep(): number | null {
    return this.count === 0 ? null : this.steps[(this.head + this.count - 1) % this.capacity]
  }

  get earliestStep(): number | null {
    return this.count === 0 ? null : this.steps[this.head]
  }

  get acceptedSteps(): readonly number[] {
    const result: number[] = []
    this.forEach((point) => result.push(point.step))
    return result
  }

  clear(): void {
    this.head = 0
    this.count = 0
    this.lastStep = Number.NEGATIVE_INFINITY
  }

  /**
   * Append only strictly newer steps. WS sequence ordering protects envelopes,
   * while this per-body guard also handles duplicate/overlapping trajectory
   * ranges after reconnect or REST resync.
   */
  append(step: number, x: number, y: number, z: number): boolean {
    if (!Number.isFinite(step) || !Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) {
      return false
    }
    if (step <= this.lastStep) return false
    const index = (this.head + this.count) % this.capacity
    this.steps[index] = step
    const coordinateIndex = index * 3
    this.coordinates[coordinateIndex] = x
    this.coordinates[coordinateIndex + 1] = y
    this.coordinates[coordinateIndex + 2] = z
    if (this.count < this.capacity) {
      this.count += 1
    } else {
      this.head = (this.head + 1) % this.capacity
    }
    this.lastStep = step
    return true
  }

  appendPoint(point: TrajectoryPointInput): boolean {
    return this.append(point.step, point.position.x, point.position.y, point.position.z)
  }

  /** Iterate in chronological order without exposing internal arrays. */
  forEach(visitor: TrajectoryPointVisitor): void {
    for (let i = 0; i < this.count; i += 1) {
      const index = (this.head + i) % this.capacity
      const coordinateIndex = index * 3
      visitor({
        step: this.steps[index],
        x: this.coordinates[coordinateIndex],
        y: this.coordinates[coordinateIndex + 1],
        z: this.coordinates[coordinateIndex + 2],
      }, i)
    }
  }

  /** Allocation-free coordinate iterator for render loops. */
  forEachCoordinates(visitor: (step: number, x: number, y: number, z: number, index: number) => void): void {
    for (let i = 0; i < this.count; i += 1) {
      const index = (this.head + i) % this.capacity
      const coordinateIndex = index * 3
      visitor(
        this.steps[index],
        this.coordinates[coordinateIndex],
        this.coordinates[coordinateIndex + 1],
        this.coordinates[coordinateIndex + 2],
        i,
      )
    }
  }

  /** Project directly while iterating, avoiding a temporary xyz array. */
  forEachProjected(projection: ProjectionPlane, visitor: ProjectedPointVisitor): void {
    this.forEach((point, index) => {
      switch (projection) {
        case 'XY':
          visitor({ step: point.step, x: point.x, y: point.y }, index)
          return
        case 'XZ':
          visitor({ step: point.step, x: point.x, y: point.z }, index)
          return
        case 'YZ':
          visitor({ step: point.step, x: point.y, y: point.z }, index)
      }
    })
  }

  /** Copy chronological xyz coordinates for callers that need a typed view. */
  toFloat64Array(): Float64Array {
    const result = new Float64Array(this.count * 3)
    let offset = 0
    this.forEach((point) => {
      result[offset] = point.x
      result[offset + 1] = point.y
      result[offset + 2] = point.z
      offset += 3
    })
    return result
  }

  toStepArray(): Float64Array {
    const result = new Float64Array(this.count)
    this.forEach((point, index) => { result[index] = point.step })
    return result
  }
}

/** Collection of per-body rings consumed by the live canvas. */
export class TrajectoryBuffer {
  readonly capacity: number
  private readonly byBody = new Map<string, BodyTrajectoryBuffer>()

  constructor(capacity = 8000) {
    this.capacity = Math.max(1, Math.floor(capacity))
  }

  get size(): number {
    return this.byBody.size
  }

  get(bodyId: string): BodyTrajectoryBuffer | undefined {
    return this.byBody.get(bodyId)
  }

  getOrCreate(bodyId: string): BodyTrajectoryBuffer {
    let buffer = this.byBody.get(bodyId)
    if (!buffer) {
      buffer = new BodyTrajectoryBuffer(this.capacity)
      this.byBody.set(bodyId, buffer)
    }
    return buffer
  }

  appendPoint(bodyId: string, point: TrajectoryPointInput): boolean {
    return this.getOrCreate(bodyId).appendPoint(point)
  }

  append(bodyId: string, step: number, position: Vector3): boolean {
    return this.getOrCreate(bodyId).append(step, position.x, position.y, position.z)
  }

  forEachBody(visitor: (bodyId: string, buffer: BodyTrajectoryBuffer) => void): void {
    this.byBody.forEach((buffer, bodyId) => visitor(bodyId, buffer))
  }

  entries(): IterableIterator<[string, BodyTrajectoryBuffer]> {
    return this.byBody.entries()
  }

  clear(): void {
    this.byBody.clear()
  }
}
