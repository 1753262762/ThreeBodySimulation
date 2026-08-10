/**
 * Mock 模式下的轻量 N 体积分器。
 *
 * 仅用于前端独立开发：实现与后端同口径的 RK4 与软化引力，
 * 使 Mock 数据具有真实的能量漂移与近距离事件行为。
 * 正式运行时所有物理计算都由 Java 服务完成，本文件不参与 live 模式。
 */
import type { Metrics, SimulationConfig, Vector3 } from '../contracts'

export interface MockBody {
  id: string
  name: string
  color: string
  massKg: number
  position: Vector3
  velocity: Vector3
}

export interface MockState {
  step: number
  simulationTimeSeconds: number
  bodies: MockBody[]
}

function zero(): Vector3 {
  return { x: 0, y: 0, z: 0 }
}

function add(a: Vector3, b: Vector3): Vector3 {
  return { x: a.x + b.x, y: a.y + b.y, z: a.z + b.z }
}

function scale(a: Vector3, factor: number): Vector3 {
  return { x: a.x * factor, y: a.y * factor, z: a.z * factor }
}

function magnitude(a: Vector3): number {
  return Math.hypot(a.x, a.y, a.z)
}

function cross(a: Vector3, b: Vector3): Vector3 {
  return {
    x: a.y * b.z - a.z * b.y,
    y: a.z * b.x - a.x * b.z,
    z: a.x * b.y - a.y * b.x,
  }
}

/** 软化引力加速度：a = G*m*r/(r^2+eps^2)^(3/2)，与后端计划一致。 */
function accelerations(
  positions: Vector3[],
  masses: number[],
  g: number,
  softening: number,
): Vector3[] {
  const eps2 = softening * softening
  const result: Vector3[] = positions.map(() => zero())
  for (let i = 0; i < positions.length; i += 1) {
    let ax = 0
    let ay = 0
    let az = 0
    for (let j = 0; j < positions.length; j += 1) {
      if (i === j) continue
      const dx = positions[j].x - positions[i].x
      const dy = positions[j].y - positions[i].y
      const dz = positions[j].z - positions[i].z
      const r2 = dx * dx + dy * dy + dz * dz + eps2
      const inv = 1 / (r2 * Math.sqrt(r2))
      const factor = g * masses[j] * inv
      ax += dx * factor
      ay += dy * factor
      az += dz * factor
    }
    result[i] = { x: ax, y: ay, z: az }
  }
  return result
}

/** 经典 RK4 单步推进，位置与速度同时积分。 */
export function rk4Step(state: MockState, config: SimulationConfig): MockState {
  const dt = config.timeStepSeconds
  const g = config.gravitationalConstant
  const eps = config.softeningLengthMeters
  const masses = state.bodies.map((body) => body.massKg)
  const p0 = state.bodies.map((body) => body.position)
  const v0 = state.bodies.map((body) => body.velocity)

  const a1 = accelerations(p0, masses, g, eps)
  const p1 = p0.map((p, i) => add(p, scale(v0[i], dt / 2)))
  const v1 = v0.map((v, i) => add(v, scale(a1[i], dt / 2)))

  const a2 = accelerations(p1, masses, g, eps)
  const p2 = p0.map((p, i) => add(p, scale(v1[i], dt / 2)))
  const v2 = v0.map((v, i) => add(v, scale(a2[i], dt / 2)))

  const a3 = accelerations(p2, masses, g, eps)
  const p3 = p0.map((p, i) => add(p, scale(v2[i], dt)))
  const v3 = v0.map((v, i) => add(v, scale(a3[i], dt)))

  const a4 = accelerations(p3, masses, g, eps)

  const bodies = state.bodies.map((body, i) => {
    const dp = scale(
      add(add(v0[i], scale(add(v1[i], v2[i]), 2)), v3[i]),
      dt / 6,
    )
    const dv = scale(
      add(add(a1[i], scale(add(a2[i], a3[i]), 2)), a4[i]),
      dt / 6,
    )
    return {
      ...body,
      position: add(body.position, dp),
      velocity: add(body.velocity, dv),
    }
  })

  return {
    step: state.step + 1,
    simulationTimeSeconds: state.simulationTimeSeconds + dt,
    bodies,
  }
}

export interface PairDistance {
  ids: [string, string]
  distanceMeters: number
}

export function closestPair(state: MockState): PairDistance | null {
  let best: PairDistance | null = null
  for (let i = 0; i < state.bodies.length; i += 1) {
    for (let j = i + 1; j < state.bodies.length; j += 1) {
      const a = state.bodies[i].position
      const b = state.bodies[j].position
      const distance = Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z)
      if (!best || distance < best.distanceMeters) {
        best = { ids: [state.bodies[i].id, state.bodies[j].id], distanceMeters: distance }
      }
    }
  }
  return best
}

export function computeMetrics(
  state: MockState,
  config: SimulationConfig,
  initialTotalEnergy: number | null,
  allTimeMinimum: { distanceMeters: number; step: number } | null,
  stepsPerSecond: number | null,
  elapsedWallClockSeconds: number | null,
): Metrics {
  let kinetic = 0
  let potential = 0
  const eps2 = config.softeningLengthMeters * config.softeningLengthMeters
  for (let i = 0; i < state.bodies.length; i += 1) {
    const body = state.bodies[i]
    kinetic += 0.5 * body.massKg * magnitude(body.velocity) ** 2
    for (let j = i + 1; j < state.bodies.length; j += 1) {
      const other = state.bodies[j]
      const dx = other.position.x - body.position.x
      const dy = other.position.y - body.position.y
      const dz = other.position.z - body.position.z
      const r = Math.sqrt(dx * dx + dy * dy + dz * dz + eps2)
      potential -= (config.gravitationalConstant * body.massKg * other.massKg) / r
    }
  }
  const total = kinetic + potential
  const initial = initialTotalEnergy ?? total

  let angular = zero()
  let linear = zero()
  for (const body of state.bodies) {
    angular = add(angular, scale(cross(body.position, body.velocity), body.massKg))
    linear = add(linear, scale(body.velocity, body.massKg))
  }

  const pair = closestPair(state)
  const drift = initial === 0 ? total : (total - initial) / Math.abs(initial)

  return {
    kineticEnergyJoules: kinetic,
    potentialEnergyJoules: potential,
    totalEnergyJoules: total,
    initialTotalEnergyJoules: initial,
    relativeEnergyDrift: drift,
    angularMomentum: angular,
    angularMomentumMagnitude: magnitude(angular),
    linearMomentum: linear,
    linearMomentumMagnitude: magnitude(linear),
    minimumPairDistanceMeters: pair?.distanceMeters ?? 0,
    minimumPairBodyIds: pair ? [pair.ids[0], pair.ids[1]] : null,
    allTimeMinimumPairDistanceMeters: allTimeMinimum?.distanceMeters ?? pair?.distanceMeters ?? null,
    allTimeMinimumPairDistanceStep: allTimeMinimum?.step ?? state.step,
    stepsPerSecond,
    elapsedWallClockSeconds,
  }
}