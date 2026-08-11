import type { QualityScale } from './renderQuality'

export interface ScreenPoint {
  readonly x: number
  readonly y: number
}

export interface TrailRenderPolicy {
  readonly toleranceCssPx: number
  readonly maxFps: number
}

/** Rendering policy for the independent trail canvas. */
export function trailRenderPolicy(quality: QualityScale): TrailRenderPolicy {
  if (quality === 1) return { toleranceCssPx: 0.5, maxFps: 60 }
  if (quality === 0.85) return { toleranceCssPx: 0.75, maxFps: 30 }
  return { toleranceCssPx: 1, maxFps: 20 }
}

function pointSegmentDistanceSquared(
  px: number,
  py: number,
  ax: number,
  ay: number,
  bx: number,
  by: number,
): number {
  const dx = bx - ax
  const dy = by - ay
  if (dx === 0 && dy === 0) {
    const ox = px - ax
    const oy = py - ay
    return ox * ox + oy * oy
  }
  const ratio = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
  const cx = ax + ratio * dx
  const cy = ay + ratio * dy
  const ox = px - cx
  const oy = py - cy
  return ox * ox + oy * oy
}

/**
 * Iterative Ramer-Douglas-Peucker simplification over an interleaved xy array.
 * Returned indices always refer to received trajectory samples; no synthetic
 * coordinates are introduced. First/last points and turns above tolerance are
 * retained.
 */
export function simplifyFlatPolylineIndices(
  coordinates: ArrayLike<number>,
  pointCount: number,
  tolerancePx = 1,
): number[] {
  const count = Math.max(0, Math.min(Math.floor(pointCount), Math.floor(coordinates.length / 2)))
  if (count === 0) return []
  if (count === 1) return [0]

  const tolerance = Math.max(0, Number.isFinite(tolerancePx) ? tolerancePx : 1)
  // Split the visual error budget between the radial pre-pass and RDP so the
  // combined approximation remains within the selected screen-space budget.
  const passTolerance = tolerance / 2
  const toleranceSquared = passTolerance * passTolerance
  // A linear radial-distance pass removes dense neighbours before RDP. Every
  // candidate is still an original sample, and smooth 60 Hz orbits avoid the
  // quadratic worst case of running RDP over thousands of sub-pixel points.
  const candidates: number[] = [0]
  let previous = 0
  for (let index = 1; index < count - 1; index += 1) {
    const dx = coordinates[index * 2] - coordinates[previous * 2]
    const dy = coordinates[index * 2 + 1] - coordinates[previous * 2 + 1]
    if (dx * dx + dy * dy > toleranceSquared) {
      candidates.push(index)
      previous = index
    }
  }
  candidates.push(count - 1)
  if (candidates.length <= 2) return candidates

  const retained = new Uint8Array(candidates.length)
  retained[0] = 1
  retained[candidates.length - 1] = 1
  const stack: number[] = [0, candidates.length - 1]

  while (stack.length > 0) {
    const end = stack.pop()!
    const start = stack.pop()!
    const startIndex = candidates[start]
    const endIndex = candidates[end]
    const ax = coordinates[startIndex * 2]
    const ay = coordinates[startIndex * 2 + 1]
    const bx = coordinates[endIndex * 2]
    const by = coordinates[endIndex * 2 + 1]
    let farthestIndex = -1
    let farthestDistance = toleranceSquared

    for (let candidateIndex = start + 1; candidateIndex < end; candidateIndex += 1) {
      const index = candidates[candidateIndex]
      const distance = pointSegmentDistanceSquared(
        coordinates[index * 2],
        coordinates[index * 2 + 1],
        ax,
        ay,
        bx,
        by,
      )
      if (distance > farthestDistance) {
        farthestDistance = distance
        farthestIndex = candidateIndex
      }
    }

    if (farthestIndex >= 0) {
      retained[farthestIndex] = 1
      stack.push(start, farthestIndex, farthestIndex, end)
    }
  }

  const result: number[] = []
  for (let index = 0; index < candidates.length; index += 1) {
    if (retained[index] === 1) result.push(candidates[index])
  }
  return result
}

/** Compatibility wrapper for callers and tests that use point objects. */
export function thinScreenPolyline(points: readonly ScreenPoint[], thresholdPx = 1): ScreenPoint[] {
  const coordinates = new Float64Array(points.length * 2)
  for (let index = 0; index < points.length; index += 1) {
    coordinates[index * 2] = points[index].x
    coordinates[index * 2 + 1] = points[index].y
  }
  return simplifyFlatPolylineIndices(coordinates, points.length, thresholdPx)
    .map((index) => points[index])
}
