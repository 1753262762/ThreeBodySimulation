export const QUALITY_STEPS = [1, 0.85, 0.7] as const
export type QualityScale = typeof QUALITY_STEPS[number]

export interface RenderQualityOptions {
  devicePixelRatio?: number
  targetFps?: number
  degradeAfterFrames?: number
  recoverAfterFrames?: number
}

export interface RenderQualityStats {
  actualFps: number | null
  targetFps: number
  p95FrameMs: number | null
  recentFrameMs: number | null
  effectiveDpr: number
  quality: QualityScale
}

function clampDpr(value: number): number {
  return Math.max(1, Math.min(2, Number.isFinite(value) ? value : 1))
}

function percentile(values: readonly number[], fraction: number): number | null {
  if (values.length === 0) return null
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.min(sorted.length - 1, Math.floor((sorted.length - 1) * fraction))]
}

/**
 * Small deterministic quality controller used by the canvas render loop. It
 * intentionally exposes all counters through `stats` so tests can feed fixed
 * frame durations without requiring a high-refresh display.
 */
export class AdaptiveQualityController {
  private baseDpr: number
  private level = 0
  private poorFrames = 0
  private stableFrames = 0
  private readonly degradeAfterFrames: number
  private readonly recoverAfterFrames: number
  private readonly durations: number[] = []
  private readonly intervals: number[] = []
  private lastTimestamp: number | null = null
  private measuredRefreshHz: number | null = null
  private configuredTargetFps: number

  constructor(options: RenderQualityOptions = {}) {
    this.baseDpr = clampDpr(options.devicePixelRatio ?? 1)
    this.configuredTargetFps = Math.max(1, Math.min(144, options.targetFps ?? 144))
    this.degradeAfterFrames = Math.max(1, Math.floor(options.degradeAfterFrames ?? 8))
    this.recoverAfterFrames = Math.max(1, Math.floor(options.recoverAfterFrames ?? 90))
  }

  get quality(): QualityScale {
    return QUALITY_STEPS[this.level]
  }

  get effectiveDpr(): number {
    return Math.max(1, Math.min(2, clampDpr(this.baseDpr) * this.quality))
  }

  get targetFps(): number {
    return Math.min(144, this.measuredRefreshHz ?? this.configuredTargetFps)
  }

  setDevicePixelRatio(value: number): void {
    this.baseDpr = clampDpr(value)
  }

  setTargetFps(value: number): void {
    if (Number.isFinite(value) && value > 0) this.configuredTargetFps = Math.min(144, value)
  }

  reset(): void {
    this.level = 0
    this.poorFrames = 0
    this.stableFrames = 0
    this.durations.length = 0
    this.intervals.length = 0
    this.lastTimestamp = null
    this.measuredRefreshHz = null
  }

  recordFrame(durationMs: number, timestampMs?: number): boolean {
    if (!Number.isFinite(durationMs) || durationMs < 0) return false
    this.durations.push(durationMs)
    if (this.durations.length > 120) this.durations.shift()

    if (timestampMs !== undefined && Number.isFinite(timestampMs)) {
      if (this.lastTimestamp !== null) {
        const interval = timestampMs - this.lastTimestamp
        if (interval > 0 && interval < 1000) {
          this.intervals.push(interval)
          if (this.intervals.length > 30) this.intervals.shift()
          if (this.intervals.length >= 8) {
            const mean = this.intervals.reduce((sum, value) => sum + value, 0) / this.intervals.length
            if (mean > 0) this.measuredRefreshHz = Math.min(144, 1000 / mean)
          }
        }
      }
      this.lastTimestamp = timestampMs
    }

    const budget = 1000 / this.targetFps
    const poor = durationMs > budget * 1.08
    const stable = durationMs < budget * 0.72
    if (poor) {
      this.poorFrames += 1
      this.stableFrames = 0
    } else if (stable) {
      this.stableFrames += 1
      this.poorFrames = 0
    } else {
      this.poorFrames = 0
      this.stableFrames = 0
    }

    let changed = false
    if (this.poorFrames >= this.degradeAfterFrames && this.level < QUALITY_STEPS.length - 1) {
      this.level += 1
      this.poorFrames = 0
      this.stableFrames = 0
      changed = true
    } else if (this.stableFrames >= this.recoverAfterFrames && this.level > 0) {
      this.level -= 1
      this.poorFrames = 0
      this.stableFrames = 0
      changed = true
    }
    return changed
  }

  get stats(): RenderQualityStats {
    return {
      actualFps: this.intervals.length === 0
        ? null
        : 1000 / (this.intervals.reduce((sum, value) => sum + value, 0) / this.intervals.length),
      targetFps: this.targetFps,
      p95FrameMs: percentile(this.durations, 0.95),
      recentFrameMs: this.durations[this.durations.length - 1] ?? null,
      effectiveDpr: this.effectiveDpr,
      quality: this.quality,
    }
  }
}

export function effectiveDevicePixelRatio(devicePixelRatio: number, quality: QualityScale): number {
  return Math.max(1, Math.min(2, clampDpr(devicePixelRatio) * quality))
}
