import { describe, expect, it } from 'vitest'
import { simplifyFlatPolylineIndices, thinScreenPolyline, trailRenderPolicy } from '../trailSampling'

describe('trail sampling', () => {
  it('按屏幕像素阈值简化并保留首尾', () => {
    const result = thinScreenPolyline([
      { x: 0, y: 0 },
      { x: 0.2, y: 0.2 },
      { x: 0.5, y: 0.4 },
      { x: 2, y: 0 },
      { x: 2.1, y: 0 },
    ], 1)
    expect(result[0]).toEqual({ x: 0, y: 0 })
    expect(result[result.length - 1]).toEqual({ x: 2.1, y: 0 })
    expect(result.length).toBeLessThan(5)
  })

  it('RDP 只返回真实样本并保留明显转折', () => {
    const points = new Float64Array([
      0, 0,
      1, 0,
      2, 4,
      3, 0,
      4, 0,
    ])
    const indices = simplifyFlatPolylineIndices(points, 5, 0.5)
    expect(indices[0]).toBe(0)
    expect(indices[indices.length - 1]).toBe(4)
    expect(indices).toContain(2)
    expect(indices.every((index) => Number.isInteger(index) && index >= 0 && index < 5)).toBe(true)
  })

  it('在容差内删除不可见的细小偏差', () => {
    const points = new Float64Array([
      0, 0,
      1, 0.2,
      2, -0.2,
      3, 0.1,
      4, 0,
    ])
    expect(simplifyFlatPolylineIndices(points, 5, 0.5)).toEqual([0, 4])
  })

  it('按自适应质量映射轨迹容差和重绘频率', () => {
    expect(trailRenderPolicy(1)).toEqual({ toleranceCssPx: 0.5, maxFps: 60 })
    expect(trailRenderPolicy(0.85)).toEqual({ toleranceCssPx: 0.75, maxFps: 30 })
    expect(trailRenderPolicy(0.7)).toEqual({ toleranceCssPx: 1, maxFps: 20 })
  })

  it('线性预筛可将8000个亚像素直线点压缩为首尾', () => {
    const points = new Float64Array(8000 * 2)
    for (let index = 0; index < 8000; index += 1) {
      points[index * 2] = index * 0.001
      points[index * 2 + 1] = 0
    }
    expect(simplifyFlatPolylineIndices(points, 8000, 0.5)).toEqual([0, 7999])
  })
})
