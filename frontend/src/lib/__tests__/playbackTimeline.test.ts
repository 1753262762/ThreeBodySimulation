import { describe, expect, it } from 'vitest'
import {
  DEFAULT_REPLAY_DURATION_SECONDS,
  REPLAY_RATES,
  adaptiveReplayStepsPerSecond,
  clampTimelineStep,
  timelineRatioFromStep,
  timelineStepFromRatio,
} from '../playbackTimeline'

describe('playback timeline mapping', () => {
  it('supports a non-zero available history origin', () => {
    expect(timelineRatioFromStep(100, 100, 500)).toBe(0)
    expect(timelineRatioFromStep(300, 100, 500)).toBe(0.5)
    expect(timelineRatioFromStep(500, 100, 500)).toBe(1)
    expect(timelineStepFromRatio(0.5, 100, 500)).toBe(300)
  })

  it('keeps step and ratio conversions inverse within integer rounding', () => {
    for (const step of [125, 333, 876, 999]) {
      const ratio = timelineRatioFromStep(step, 125, 999)
      expect(timelineStepFromRatio(ratio, 125, 999)).toBe(step)
    }
  })

  it('clamps out-of-range and invalid input', () => {
    expect(clampTimelineStep(-5, 10, 20)).toBe(10)
    expect(clampTimelineStep(30, 10, 20)).toBe(20)
    expect(timelineStepFromRatio(-1, 10, 20)).toBe(10)
    expect(timelineStepFromRatio(2, 10, 20)).toBe(20)
  })
})

describe('adaptive replay speed', () => {
  it('traverses the captured range in sixty seconds at 1×', () => {
    const stepsPerSecond = adaptiveReplayStepsPerSecond(1_000, 61_000, 1)
    expect(stepsPerSecond * DEFAULT_REPLAY_DURATION_SECONDS).toBe(60_000)
  })

  it('supports all five speed multipliers', () => {
    expect(REPLAY_RATES).toEqual([0.25, 0.5, 1, 2, 4])
    const base = adaptiveReplayStepsPerSecond(0, 600, 1)
    for (const rate of REPLAY_RATES) {
      expect(adaptiveReplayStepsPerSecond(0, 600, rate)).toBe(base * rate)
    }
  })
})
