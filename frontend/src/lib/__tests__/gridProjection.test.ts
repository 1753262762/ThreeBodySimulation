import { describe, expect, it } from 'vitest'
import { projectGridPoint, visibleGridBounds } from '../gridProjection'

describe('gridProjection', () => {
  it('原点坐标轴与 project 对齐，DPR 不重复放大偏移', () => {
    const transform = { width: 400, height: 200, scale: 2, offsetX: 37, offsetY: 23 }
    expect(projectGridPoint(0, 0, transform)).toEqual([37, 23])
    expect(visibleGridBounds(transform)).toEqual({
      left: -18.5,
      right: 181.5,
      top: 11.5,
      bottom: -88.5,
    })
    // Physical dimensions may already be DPR=2; offsets remain physical and
    // must not be multiplied by DPR a second time.
    const dprTwo = { ...transform, width: 800, height: 400 }
    expect(projectGridPoint(0, 0, dprTwo)).toEqual([37, 23])
  })
})
