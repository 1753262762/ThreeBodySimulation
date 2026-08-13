export interface CanvasView {
  scale: number
  offsetX: number
  offsetY: number
}

export interface CanvasSize {
  width: number
  height: number
}

export interface ProjectedWorldPoint {
  horizontal: number
  vertical: number
}

/** 使用世界坐标计算适应视图，避免把已投影像素再次作为世界坐标。 */
export function fitCanvasView(
  points: readonly ProjectedWorldPoint[],
  size: CanvasSize,
  paddingRatio = 0.2,
): CanvasView | null {
  if (points.length === 0 || size.width <= 0 || size.height <= 0) return null
  let minX = Number.POSITIVE_INFINITY
  let maxX = Number.NEGATIVE_INFINITY
  let minY = Number.POSITIVE_INFINITY
  let maxY = Number.NEGATIVE_INFINITY
  for (const point of points) {
    if (!Number.isFinite(point.horizontal) || !Number.isFinite(point.vertical)) continue
    minX = Math.min(minX, point.horizontal)
    maxX = Math.max(maxX, point.horizontal)
    minY = Math.min(minY, point.vertical)
    maxY = Math.max(maxY, point.vertical)
  }
  if (![minX, maxX, minY, maxY].every(Number.isFinite)) return null

  const spanX = Math.max(1e-9, maxX - minX)
  const spanY = Math.max(1e-9, maxY - minY)
  const scale = Math.min(
    size.width / (spanX * (1 + paddingRatio)),
    size.height / (spanY * (1 + paddingRatio)),
  )
  const centerX = (minX + maxX) / 2
  const centerY = (minY + maxY) / 2
  return {
    scale,
    offsetX: size.width / 2 - centerX * scale,
    offsetY: size.height / 2 + centerY * scale,
  }
}

/** Backing store 或 DPR 改变后保留世界中心，并保持每 CSS 像素的视觉尺度。 */
export function resizeCanvasView(
  view: CanvasView,
  previousSize: CanvasSize,
  nextSize: CanvasSize,
  previousDpr: number,
  nextDpr: number,
): CanvasView {
  if (
    previousSize.width <= 0 || previousSize.height <= 0 ||
    nextSize.width <= 0 || nextSize.height <= 0 ||
    !Number.isFinite(view.scale) || view.scale === 0
  ) {
    return { ...view }
  }
  const safePreviousDpr = previousDpr > 0 ? previousDpr : 1
  const safeNextDpr = nextDpr > 0 ? nextDpr : 1
  const centerWorldX = (previousSize.width / 2 - view.offsetX) / view.scale
  const centerWorldY = -(previousSize.height / 2 - view.offsetY) / view.scale
  const scale = view.scale * (safeNextDpr / safePreviousDpr)
  return {
    scale,
    offsetX: nextSize.width / 2 - centerWorldX * scale,
    offsetY: nextSize.height / 2 + centerWorldY * scale,
  }
}
