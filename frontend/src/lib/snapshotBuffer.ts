import type { BodyState, SimulationState, Vector3 } from '../contracts'

/**
 * A snapshot together with the local monotonic time at which it arrived.
 * Arrival time is deliberately kept separate from simulation time: the former
 * is used by the display clock while the latter is used for velocity tangents.
 */
export interface SnapshotFrame {
  readonly state: SimulationState
  readonly arrivalTimeMs: number
}

export interface SnapshotBufferOptions {
  /** Optional upper bound for the display delay. The default adapts to one frame. */
  interpolationDelayMs?: number
}

const DEFAULT_MAX_INTERPOLATION_DELAY_MS = 50

function nowMs(): number {
  if (typeof performance !== 'undefined' && Number.isFinite(performance.now())) {
    return performance.now()
  }
  return Date.now()
}

function finiteVector(value: Vector3 | null | undefined): value is Vector3 {
  return value !== null
    && value !== undefined
    && Number.isFinite(value.x)
    && Number.isFinite(value.y)
    && Number.isFinite(value.z)
}

function cloneVector(value: Vector3): Vector3 {
  return { x: value.x, y: value.y, z: value.z }
}

function cloneBody(body: BodyState): BodyState {
  return {
    id: body.id,
    position: finiteVector(body.position) ? cloneVector(body.position) : { x: 0, y: 0, z: 0 },
    velocity: finiteVector(body.velocity) ? cloneVector(body.velocity) : { x: 0, y: 0, z: 0 },
  }
}

/** Clone a state so display interpolation can never mutate the authoritative state. */
export function cloneSimulationState(state: SimulationState): SimulationState {
  return {
    step: state.step,
    simulationTimeSeconds: state.simulationTimeSeconds,
    bodies: state.bodies.map(cloneBody),
  }
}

function clamp01(value: number): number {
  return Math.max(0, Math.min(1, value))
}

function linear(a: number, b: number, alpha: number): number {
  return a + (b - a) * alpha
}

function constrainedHermite(
  p0: number,
  p1: number,
  v0: number,
  v1: number,
  dt: number,
  alpha: number,
): number {
  const t = clamp01(alpha)
  const t2 = t * t
  const t3 = t2 * t
  const h00 = 2 * t3 - 3 * t2 + 1
  const h10 = t3 - 2 * t2 + t
  const h01 = -2 * t3 + 3 * t2
  const h11 = t3 - t2
  const value = h00 * p0 + h10 * v0 * dt + h01 * p1 + h11 * v1 * dt
  // A cubic with noisy velocities can overshoot the interval. Constrain each
  // coordinate to the two received positions before it reaches the renderer.
  const min = Math.min(p0, p1)
  const max = Math.max(p0, p1)
  return Math.max(min, Math.min(max, Number.isFinite(value) ? value : linear(p0, p1, t)))
}

function interpolateBody(previous: BodyState | undefined, current: BodyState, alpha: number, dt: number): BodyState {
  const currentValid = finiteVector(current.position)
  const previousValid = previous !== undefined && finiteVector(previous.position)
  if (!currentValid) {
    // A malformed current body must not poison the display. Prefer the last
    // valid position, otherwise return a finite zero body.
    return previous && previousValid ? cloneBody(previous) : cloneBody(current)
  }
  if (!previous || !previousValid || previous.id !== current.id) return cloneBody(current)

  const t = clamp01(alpha)
  const velocityValid = finiteVector(previous.velocity) && finiteVector(current.velocity)
  const position = velocityValid && dt > 0 && Number.isFinite(dt)
    ? {
        x: constrainedHermite(previous.position.x, current.position.x, previous.velocity.x, current.velocity.x, dt, t),
        y: constrainedHermite(previous.position.y, current.position.y, previous.velocity.y, current.velocity.y, dt, t),
        z: constrainedHermite(previous.position.z, current.position.z, previous.velocity.z, current.velocity.z, dt, t),
      }
    : {
        x: linear(previous.position.x, current.position.x, t),
        y: linear(previous.position.y, current.position.y, t),
        z: linear(previous.position.z, current.position.z, t),
      }
  const velocity = velocityValid
    ? {
        x: linear(previous.velocity.x, current.velocity.x, t),
        y: linear(previous.velocity.y, current.velocity.y, t),
        z: linear(previous.velocity.z, current.velocity.z, t),
      }
    : cloneBody(current).velocity
  return { id: current.id, position, velocity }
}

/**
 * Interpolate two frames at a normalized alpha. The returned object is always
 * detached from both input frames, making it safe for a canvas to consume.
 */
export function interpolateSnapshots(
  previous: SimulationState,
  current: SimulationState,
  alpha: number,
): SimulationState {
  const t = clamp01(Number.isFinite(alpha) ? alpha : 1)
  const previousById = new Map(previous.bodies.map((body) => [body.id, body]))
  const simulationTimeSeconds = Number.isFinite(previous.simulationTimeSeconds)
    && Number.isFinite(current.simulationTimeSeconds)
    ? linear(previous.simulationTimeSeconds, current.simulationTimeSeconds, t)
    : current.simulationTimeSeconds
  const dt = current.simulationTimeSeconds - previous.simulationTimeSeconds
  return {
    // A step is an integer authoritative marker. Keep the current marker once
    // the display reaches it; no physical state is ever written back here.
    step: t >= 1 ? current.step : previous.step,
    simulationTimeSeconds,
    bodies: current.bodies.map((body) => interpolateBody(previousById.get(body.id), body, t, dt)),
  }
}

/**
 * Two-frame display buffer. `push` accepts monotonically increasing steps and
 * keeps the previous/current pair; `readInterpolated` clamps the display clock
 * to the available interval and therefore never extrapolates indefinitely.
 */
export class SnapshotBuffer {
  private previousFrame: SnapshotFrame | null = null
  private currentFrame: SnapshotFrame | null = null
  private observedIntervalMs: number | null = null
  private readonly configuredDelayMs: number | null

  constructor(options: SnapshotBufferOptions = {}) {
    this.configuredDelayMs = options.interpolationDelayMs === undefined
      ? null
      : Math.max(0, options.interpolationDelayMs)
  }

  get previous(): SnapshotFrame | null {
    return this.previousFrame
  }

  get current(): SnapshotFrame | null {
    return this.currentFrame
  }

  get hasFrame(): boolean {
    return this.currentFrame !== null
  }

  /** Latest observed local arrival interval, used as the one-frame delay. */
  get observedArrivalIntervalMs(): number | null {
    return this.observedIntervalMs
  }

  /** Effective delay is one observed frame, capped by an explicit upper bound. */
  get interpolationDelayMs(): number {
    const upperBound = this.configuredDelayMs ?? DEFAULT_MAX_INTERPOLATION_DELAY_MS
    return Math.min(upperBound, this.observedIntervalMs ?? upperBound)
  }

  reset(): void {
    this.previousFrame = null
    this.currentFrame = null
    this.observedIntervalMs = null
  }

  push(state: SimulationState, arrivalTimeMs = nowMs()): boolean {
    if (!Number.isFinite(arrivalTimeMs)) return false
    const cloned = cloneSimulationState(state)
    const frame: SnapshotFrame = { state: cloned, arrivalTimeMs }
    const current = this.currentFrame
    if (current && Number.isFinite(current.state.step) && Number.isFinite(state.step)) {
      if (state.step < current.state.step) return false
      if (state.step === current.state.step) {
        // REST resync can return the same step as the last WS snapshot. Replace
        // that current frame without manufacturing a duplicate interpolation.
        this.currentFrame = frame
        return true
      }
    }
    this.previousFrame = current
    this.currentFrame = frame
    if (current) {
      const interval = arrivalTimeMs - current.arrivalTimeMs
      if (Number.isFinite(interval) && interval > 0) {
        this.observedIntervalMs = interval
      }
    }
    return true
  }

  /** Read at an explicit display clock; useful for deterministic tests. */
  readInterpolated(atTimeMs = nowMs(), delayMs?: number): SimulationState | null {
    const current = this.currentFrame
    if (!current) return null
    const previous = this.previousFrame
    if (!previous) return cloneSimulationState(current.state)

    const currentArrival = current.arrivalTimeMs
    const previousArrival = previous.arrivalTimeMs
    if (!Number.isFinite(atTimeMs) || !Number.isFinite(currentArrival) || !Number.isFinite(previousArrival)
      || currentArrival <= previousArrival) {
      return cloneSimulationState(current.state)
    }
    const effectiveDelay = delayMs === undefined
      ? this.interpolationDelayMs
      : Math.max(0, Number.isFinite(delayMs) ? delayMs : 0)
    const displayTime = atTimeMs - effectiveDelay
    const alpha = clamp01((displayTime - previousArrival) / (currentArrival - previousArrival))
    return interpolateSnapshots(previous.state, current.state, alpha)
  }

  /** Whether the delayed display clock still needs a frame before current. */
  hasPendingInterpolation(atTimeMs = nowMs(), delayMs?: number): boolean {
    if (!this.previousFrame || !this.currentFrame || !Number.isFinite(atTimeMs)) return false
    const effectiveDelay = delayMs === undefined
      ? this.interpolationDelayMs
      : Math.max(0, Number.isFinite(delayMs) ? delayMs : 0)
    return atTimeMs - effectiveDelay < this.currentFrame.arrivalTimeMs
  }

  isInterpolationPending(atTimeMs = nowMs(), delayMs?: number): boolean {
    return this.hasPendingInterpolation(atTimeMs, delayMs)
  }

  /** Alias kept intentionally terse for canvas/render-loop callers. */
  read(atTimeMs = nowMs(), delayMs?: number): SimulationState | null {
    return this.readInterpolated(atTimeMs, delayMs)
  }
}

export const DEFAULT_SNAPSHOT_INTERPOLATION_DELAY_MS = DEFAULT_MAX_INTERPOLATION_DELAY_MS
