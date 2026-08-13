import type { Metrics, SimulationConfig, Vector3 } from '../contracts'

export type ConservationLevel = 'STABLE' | 'NOTICE' | 'WARNING' | 'CRITICAL' | 'UNAVAILABLE'

export interface AngularMomentumHealth {
  initial: Vector3
  initialMagnitude: number
  relativeDrift: number | null
  level: ConservationLevel
}

const NOTICE_THRESHOLD = 1e-3
const WARNING_THRESHOLD = 1e-2
const CRITICAL_THRESHOLD = 5e-2
const NEAR_ZERO_RATIO = 1e-12

export function conservationLevel(relativeDrift: number | null): ConservationLevel {
  if (relativeDrift === null || !Number.isFinite(relativeDrift)) return 'UNAVAILABLE'

  const magnitude = Math.abs(relativeDrift)
  if (magnitude >= CRITICAL_THRESHOLD) return 'CRITICAL'
  if (magnitude >= WARNING_THRESHOLD) return 'WARNING'
  if (magnitude >= NOTICE_THRESHOLD) return 'NOTICE'
  return 'STABLE'
}

function magnitude(vector: Vector3): number {
  return Math.hypot(vector.x, vector.y, vector.z)
}

function angularContribution(
  massKg: number,
  position: Vector3,
  velocity: Vector3,
): Vector3 {
  return {
    x: massKg * (position.y * velocity.z - position.z * velocity.y),
    y: massKg * (position.z * velocity.x - position.x * velocity.z),
    z: massKg * (position.x * velocity.y - position.y * velocity.x),
  }
}

export function angularMomentumHealth(
  config: SimulationConfig | null,
  metrics: Metrics | null,
): AngularMomentumHealth | null {
  if (!config || !metrics) return null

  const initial = { x: 0, y: 0, z: 0 }
  let contributionScale = 0
  for (const body of config.bodies) {
    const contribution = angularContribution(body.massKg, body.position, body.velocity)
    initial.x += contribution.x
    initial.y += contribution.y
    initial.z += contribution.z
    contributionScale += magnitude(contribution)
  }

  const initialMagnitude = magnitude(initial)
  const reference = initialMagnitude > contributionScale * NEAR_ZERO_RATIO
    ? initialMagnitude
    : contributionScale
  const current = metrics.angularMomentum
  const delta = magnitude({
    x: current.x - initial.x,
    y: current.y - initial.y,
    z: current.z - initial.z,
  })
  const relativeDrift = Number.isFinite(delta) && Number.isFinite(reference) && reference > 0
    ? delta / reference
    : null

  return { initial, initialMagnitude, relativeDrift, level: conservationLevel(relativeDrift) }
}
