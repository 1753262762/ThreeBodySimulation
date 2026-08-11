import { describe, expect, it } from 'vitest'
import { AdaptiveQualityController, effectiveDevicePixelRatio } from '../renderQuality'

describe('AdaptiveQualityController', () => {
  it('在持续掉帧时逐级降质，并在稳定窗口后恢复', () => {
    const quality = new AdaptiveQualityController({
      devicePixelRatio: 2,
      targetFps: 144,
      degradeAfterFrames: 3,
      recoverAfterFrames: 4,
    })
    expect(quality.effectiveDpr).toBe(2)
    for (let i = 0; i < 3; i += 1) quality.recordFrame(20, i * 7)
    expect(quality.quality).toBe(0.85)
    for (let i = 0; i < 3; i += 1) quality.recordFrame(20, (i + 3) * 7)
    expect(quality.quality).toBe(0.7)
    for (let i = 0; i < 4; i += 1) quality.recordFrame(1, (i + 6) * 7)
    expect(quality.quality).toBe(0.85)
    expect(quality.effectiveDpr).toBeCloseTo(1.7)
  })

  it('有效 DPR 始终限制在 1–2 的基础范围并应用质量阶梯', () => {
    expect(effectiveDevicePixelRatio(4, 1)).toBe(2)
    expect(effectiveDevicePixelRatio(2, 0.7)).toBeCloseTo(1.4)
    expect(effectiveDevicePixelRatio(0.2, 0.7)).toBe(1)
  })
})
