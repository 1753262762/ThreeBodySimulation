export const REPLAY_RATES = [0.25, 0.5, 1, 2, 4] as const
export type ReplayRate = typeof REPLAY_RATES[number]

export const DEFAULT_REPLAY_RATE: ReplayRate = 1
export const DEFAULT_REPLAY_DURATION_SECONDS = 60

export function clampTimelineStep(step: number, fromStep: number, toStep: number): number {
  const lower = Number.isFinite(fromStep) ? Math.floor(fromStep) : 0
  const upper = Number.isFinite(toStep) ? Math.max(lower, Math.floor(toStep)) : lower
  const value = Number.isFinite(step) ? Math.round(step) : lower
  return Math.min(upper, Math.max(lower, value))
}

export function timelineRatioFromStep(step: number, fromStep: number, toStep: number): number {
  const lower = Number.isFinite(fromStep) ? Math.floor(fromStep) : 0
  const upper = Number.isFinite(toStep) ? Math.max(lower, Math.floor(toStep)) : lower
  if (upper === lower) return 0
  return (clampTimelineStep(step, lower, upper) - lower) / (upper - lower)
}

export function timelineStepFromRatio(ratio: number, fromStep: number, toStep: number): number {
  const lower = Number.isFinite(fromStep) ? Math.floor(fromStep) : 0
  const upper = Number.isFinite(toStep) ? Math.max(lower, Math.floor(toStep)) : lower
  const boundedRatio = Math.min(1, Math.max(0, Number.isFinite(ratio) ? ratio : 0))
  return clampTimelineStep(lower + (upper - lower) * boundedRatio, lower, upper)
}

/** At 1×, traverse the range captured at playback start in roughly one minute. */
export function adaptiveReplayStepsPerSecond(
  fromStep: number,
  toStep: number,
  rate: ReplayRate = DEFAULT_REPLAY_RATE,
): number {
  const range = Math.max(1, Math.floor(toStep) - Math.floor(fromStep))
  return Math.max(1, range / DEFAULT_REPLAY_DURATION_SECONDS) * rate
}
