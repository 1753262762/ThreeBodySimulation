import { describe, expect, it } from 'vitest'
import { fitCanvasView, resizeCanvasView } from '../canvasViewport'

describe('canvasViewport', () => {
  it('fit 使用世界坐标且重复计算幂等', () => {
    const points = [
      { horizontal: -10, vertical: -5 },
      { horizontal: 10, vertical: 5 },
    ]
    const first = fitCanvasView(points, { width: 1200, height: 600 })
    const second = fitCanvasView(points, { width: 1200, height: 600 })
    expect(first).toEqual(second)
    expect(first).toEqual({ scale: 50, offsetX: 600, offsetY: 300 })
  })

  it('resize 保留世界中心和 CSS 视觉尺度', () => {
    const resized = resizeCanvasView(
      { scale: 20, offsetX: 300, offsetY: 150 },
      { width: 600, height: 300 },
      { width: 1600, height: 800 },
      1,
      2,
    )
    expect(resized).toEqual({ scale: 40, offsetX: 800, offsetY: 400 })
    expect(resized.scale / 2).toBe(20)
  })

  it('resize 在非原点世界中心下仍保持中心', () => {
    const resized = resizeCanvasView(
      { scale: 10, offsetX: 100, offsetY: 250 },
      { width: 600, height: 400 },
      { width: 900, height: 600 },
      1,
      1,
    )
    expect((450 - resized.offsetX) / resized.scale).toBe(20)
    expect(-(300 - resized.offsetY) / resized.scale).toBe(5)
  })
})
