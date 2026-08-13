import type { SimulationState, Vector3 } from '../contracts'
import type { BodyTrajectoryBuffer } from './trajectoryBuffer'

export const DEFAULT_SCENE_SPAN = 24
export const MAX_3D_TRAIL_POINTS = 8000
export const BASE_BODY_RADIUS = 0.18
export const FREE_CAMERA_MAX_DISTANCE_MULTIPLIER = 100
export const BODY_ATMOSPHERE_SCALE = 1.075

export interface SceneTransform {
  readonly origin: Vector3
  readonly metersPerUnit: number
}

export type SceneCameraPreset = 'FREE' | 'XY' | 'XZ' | 'YZ'

export interface CameraPresetPose {
  readonly direction: Vector3
  readonly up: Vector3
}

export interface PerspectiveCameraFrame {
  readonly distance: number
  readonly near: number
  readonly far: number
  readonly minDistance: number
  readonly maxDistance: number
}

export interface OrthographicCameraFrame {
  readonly distance: number
  readonly halfHeight: number
  readonly near: number
  readonly far: number
  readonly minZoom: number
  readonly maxZoom: number
}

export interface TrajectoryRenderCache {
  readonly positions: Float32Array
  pointCount: number
  sourcePointCount: number
  sourceEarliestStep: number | null
  visibleLatestStep: number | null
  cutoffStep: number
  transformKey: string
}

function finiteVector(value: Vector3): boolean {
  return Number.isFinite(value.x) && Number.isFinite(value.y) && Number.isFinite(value.z)
}

/** Establish a frozen SI-to-scene transform only after the state has a real extent. */
export function createSceneTransform(
  state: SimulationState | null,
  targetSpan = DEFAULT_SCENE_SPAN,
): SceneTransform | null {
  const positions = state?.bodies.map((body) => body.position).filter(finiteVector) ?? []
  if (positions.length === 0) return null

  let minX = positions[0].x
  let maxX = positions[0].x
  let minY = positions[0].y
  let maxY = positions[0].y
  let minZ = positions[0].z
  let maxZ = positions[0].z
  for (let index = 1; index < positions.length; index += 1) {
    const position = positions[index]
    minX = Math.min(minX, position.x)
    maxX = Math.max(maxX, position.x)
    minY = Math.min(minY, position.y)
    maxY = Math.max(maxY, position.y)
    minZ = Math.min(minZ, position.z)
    maxZ = Math.max(maxZ, position.z)
  }
  const maxSpanMeters = Math.max(maxX - minX, maxY - minY, maxZ - minZ)
  if (!(maxSpanMeters > 0) || !Number.isFinite(maxSpanMeters)) return null
  const normalizedTargetSpan = Number.isFinite(targetSpan) && targetSpan > 0
    ? targetSpan
    : DEFAULT_SCENE_SPAN
  return {
    origin: {
      x: (minX + maxX) / 2,
      y: (minY + maxY) / 2,
      z: (minZ + maxZ) / 2,
    },
    metersPerUnit: maxSpanMeters / normalizedTargetSpan,
  }
}

export function transformScenePosition(position: Vector3, transform: SceneTransform): Vector3 {
  return {
    x: (position.x - transform.origin.x) / transform.metersPerUnit,
    y: (position.y - transform.origin.y) / transform.metersPerUnit,
    z: (position.z - transform.origin.z) / transform.metersPerUnit,
  }
}

export function sceneTransformKey(transform: SceneTransform): string {
  const { origin, metersPerUnit } = transform
  return `${origin.x}:${origin.y}:${origin.z}:${metersPerUnit}`
}

/** All bodies use one stable symbolic size; simulation mass never changes rendering scale. */
export function visualBodyRadius(): number {
  return BASE_BODY_RADIUS
}

export function effective3dDevicePixelRatio(devicePixelRatio: number): number {
  return Math.max(1, Math.min(2, Number.isFinite(devicePixelRatio) ? devicePixelRatio : 1))
}

export function cameraPresetPose(preset: SceneCameraPreset): CameraPresetPose {
  switch (preset) {
    case 'XY': return { direction: { x: 0, y: 0, z: 1 }, up: { x: 0, y: 1, z: 0 } }
    case 'XZ': return { direction: { x: 0, y: -1, z: 0 }, up: { x: 0, y: 0, z: 1 } }
    case 'YZ': return { direction: { x: 1, y: 0, z: 0 }, up: { x: 0, y: 0, z: 1 } }
    case 'FREE': return { direction: { x: 1, y: -0.8, z: 0.75 }, up: { x: 0, y: 0, z: 1 } }
  }
}

function normalizedRadius(radius: number): number {
  return Number.isFinite(radius) && radius > 0 ? radius : 1
}

function normalizedAspect(aspect: number): number {
  return Number.isFinite(aspect) && aspect > 0 ? aspect : 1
}

/** Fit a sphere in both the horizontal and vertical perspective FOV. */
export function perspectiveCameraFrame(
  radius: number,
  aspect: number,
  largestBodyRadius: number,
  verticalFovDegrees = 45,
): PerspectiveCameraFrame {
  const safeRadius = normalizedRadius(radius)
  const safeAspect = normalizedAspect(aspect)
  const halfVerticalFov = Math.max(0.05, Math.min(Math.PI / 2 - 0.01, verticalFovDegrees * Math.PI / 360))
  const halfHorizontalFov = Math.atan(Math.tan(halfVerticalFov) * safeAspect)
  const limitingHalfFov = Math.min(halfVerticalFov, halfHorizontalFov)
  const distance = safeRadius * 1.25 / Math.sin(limitingHalfFov)
  const maxDistance = safeRadius * FREE_CAMERA_MAX_DISTANCE_MULTIPLIER
  const minDistance = Math.max(0.05, safeRadius * 0.02, Math.max(0, largestBodyRadius) * 2.5)
  const near = Math.max(0.001, safeRadius / 1000)
  const far = Math.max(100, maxDistance + safeRadius * 3)
  return { distance, near, far, minDistance, maxDistance }
}

/** Keep perspective clipping valid while OrbitControls changes camera distance. */
export function perspectiveClipping(distance: number, radius: number): { near: number; far: number } {
  const safeRadius = normalizedRadius(radius)
  const safeDistance = Number.isFinite(distance) && distance > 0 ? distance : safeRadius
  return {
    near: Math.max(0.001, Math.min(safeRadius / 1000, safeDistance / 100)),
    far: Math.max(100, safeRadius * (FREE_CAMERA_MAX_DISTANCE_MULTIPLIER + 3), safeDistance + safeRadius * 3),
  }
}

/** Orthographic presets fit the same sphere without perspective shortening. */
export function orthographicCameraFrame(radius: number, aspect: number): OrthographicCameraFrame {
  const safeRadius = normalizedRadius(radius)
  const safeAspect = normalizedAspect(aspect)
  return {
    distance: Math.max(6, safeRadius * 3),
    halfHeight: safeRadius * 1.25 * Math.max(1, 1 / safeAspect),
    near: 0.001,
    far: Math.max(100, safeRadius * (FREE_CAMERA_MAX_DISTANCE_MULTIPLIER + 3)),
    minZoom: 0.01,
    maxZoom: 50,
  }
}

export function createTrajectoryRenderCache(capacity: number): TrajectoryRenderCache {
  const pointCapacity = Math.max(1, Math.min(MAX_3D_TRAIL_POINTS, Math.floor(capacity)))
  return {
    positions: new Float32Array(pointCapacity * 3),
    pointCount: 0,
    sourcePointCount: 0,
    sourceEarliestStep: null,
    visibleLatestStep: null,
    cutoffStep: Number.NEGATIVE_INFINITY,
    transformKey: '',
  }
}

/**
 * Synchronize one trajectory into its preallocated xyz attribute. Appends update
 * only the new tail; ring overwrite, cursor rollback, or transform changes rebuild.
 */
export function syncTrajectoryRenderCache(
  source: BodyTrajectoryBuffer,
  transform: SceneTransform,
  cutoffStep: number,
  cache: TrajectoryRenderCache,
): { rebuilt: boolean; pointCount: number } {
  const nextTransformKey = sceneTransformKey(transform)
  const ringOverwritten = cache.sourceEarliestStep !== null && source.earliestStep !== cache.sourceEarliestStep
  const cursorRolledBack = cutoffStep < cache.cutoffStep
  const sourceShrank = source.pointCount < cache.sourcePointCount
  const rebuild = cache.transformKey !== nextTransformKey || ringOverwritten || cursorRolledBack || sourceShrank
  let written = rebuild ? 0 : cache.pointCount
  let visibleLatestStep = rebuild ? null : cache.visibleLatestStep

  source.forEachCoordinates((step, x, y, z, index) => {
    if (step > cutoffStep || written >= cache.positions.length / 3) return
    if (!rebuild && index < cache.pointCount) return
    const transformed = transformScenePosition({ x, y, z }, transform)
    const offset = written * 3
    cache.positions[offset] = transformed.x
    cache.positions[offset + 1] = transformed.y
    cache.positions[offset + 2] = transformed.z
    written += 1
    visibleLatestStep = step
  })

  cache.pointCount = written
  cache.sourcePointCount = source.pointCount
  cache.sourceEarliestStep = source.earliestStep
  cache.visibleLatestStep = visibleLatestStep
  cache.cutoffStep = cutoffStep
  cache.transformKey = nextTransformKey
  return { rebuilt: rebuild, pointCount: written }
}
