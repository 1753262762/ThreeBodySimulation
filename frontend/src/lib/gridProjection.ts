export interface GridTransform {
  readonly width: number
  readonly height: number
  readonly scale: number
  readonly offsetX: number
  readonly offsetY: number
}

/** Project a point in the already-selected 2D projection into canvas pixels. */
export function projectGridPoint(x: number, y: number, transform: GridTransform): [number, number] {
  return [
    x * transform.scale + transform.offsetX,
    -y * transform.scale + transform.offsetY,
  ]
}

/** World coordinates visible at the four physical canvas edges. */
export function visibleGridBounds(transform: GridTransform): {
  left: number
  right: number
  top: number
  bottom: number
} {
  const scale = Math.max(Number.EPSILON, Math.abs(transform.scale))
  return {
    left: (0 - transform.offsetX) / scale,
    right: (transform.width - transform.offsetX) / scale,
    top: transform.offsetY / scale,
    bottom: (transform.offsetY - transform.height) / scale,
  }
}
