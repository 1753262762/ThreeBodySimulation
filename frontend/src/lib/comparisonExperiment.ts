import type { SimulationConfig } from '../contracts'

export const MAX_ALLOWED_STEPS = 100_000_000

export type EndConditionMode = 'MAX_STEPS' | 'TARGET_TIME' | 'BOTH'

export interface ComparisonExperimentPlan {
  sourceConfig: SimulationConfig
  proposedConfig: SimulationConfig
  mode: EndConditionMode
  originalTimeStepSeconds: number
  proposedTimeStepSeconds: number
  originalMaxSteps: number | null
  proposedMaxSteps: number | null
  originalEstimatedSteps: number
  proposedEstimatedSteps: number
  originalSimulationTimeSeconds: number
  proposedSimulationTimeSeconds: number
  relativeStepCount: number
  exceedsStepLimit: boolean
  minimumTimeStepPreservingDurationSeconds: number | null
}

export type StepLimitResolution = 'SHORTEN_DURATION' | 'PRESERVE_DURATION'

export function endConditionMode(config: SimulationConfig): EndConditionMode {
  if (config.maxSteps != null && config.targetSimulationTimeSeconds != null) return 'BOTH'
  return config.maxSteps != null ? 'MAX_STEPS' : 'TARGET_TIME'
}

export function estimatedSteps(config: SimulationConfig): number {
  const byMax = config.maxSteps ?? Number.POSITIVE_INFINITY
  const byTime = config.targetSimulationTimeSeconds == null
    ? Number.POSITIVE_INFINITY
    : Math.ceil(config.targetSimulationTimeSeconds / config.timeStepSeconds)
  return Math.min(byMax, byTime)
}

/**
 * 只规划数值控制项。默认同比调整 maxSteps，以保持源运行记录的模拟覆盖时间。
 * 超过一亿步时仅报告冲突，不静默截断。
 */
export function planComparisonExperiment(
  sourceConfig: SimulationConfig,
  proposedTimeStepSeconds: number,
): ComparisonExperimentPlan {
  if (!(proposedTimeStepSeconds > 0) || !Number.isFinite(proposedTimeStepSeconds)) {
    throw new Error('建议时间步长必须是大于 0 的有限值。')
  }
  const mode = endConditionMode(sourceConfig)
  const originalEstimatedSteps = estimatedSteps(sourceConfig)
  const originalDuration = originalEstimatedSteps * sourceConfig.timeStepSeconds
  const scaledMaxSteps = sourceConfig.maxSteps == null
    ? null
    : Math.ceil(sourceConfig.maxSteps * sourceConfig.timeStepSeconds / proposedTimeStepSeconds)
  const proposedConfig: SimulationConfig = {
    ...sourceConfig,
    timeStepSeconds: proposedTimeStepSeconds,
    maxSteps: scaledMaxSteps,
  }
  const proposedEstimatedSteps = estimatedSteps(proposedConfig)
  const exceedsStepLimit = (scaledMaxSteps != null && scaledMaxSteps > MAX_ALLOWED_STEPS)
    || proposedEstimatedSteps > MAX_ALLOWED_STEPS
  const maxStepFloor = sourceConfig.maxSteps == null
    ? 0
    : sourceConfig.maxSteps * sourceConfig.timeStepSeconds / MAX_ALLOWED_STEPS
  const targetFloor = sourceConfig.targetSimulationTimeSeconds == null
    ? 0
    : sourceConfig.targetSimulationTimeSeconds / MAX_ALLOWED_STEPS
  const minimumTimeStep = Math.max(maxStepFloor, targetFloor)
  return {
    sourceConfig,
    proposedConfig,
    mode,
    originalTimeStepSeconds: sourceConfig.timeStepSeconds,
    proposedTimeStepSeconds,
    originalMaxSteps: sourceConfig.maxSteps ?? null,
    proposedMaxSteps: scaledMaxSteps,
    originalEstimatedSteps,
    proposedEstimatedSteps,
    originalSimulationTimeSeconds: originalDuration,
    proposedSimulationTimeSeconds: proposedEstimatedSteps * proposedTimeStepSeconds,
    relativeStepCount: originalEstimatedSteps > 0
      ? proposedEstimatedSteps / originalEstimatedSteps
      : 1,
    exceedsStepLimit,
    minimumTimeStepPreservingDurationSeconds: minimumTimeStep > 0 ? minimumTimeStep : null,
  }
}

export function resolveStepLimit(
  plan: ComparisonExperimentPlan,
  resolution: StepLimitResolution,
): ComparisonExperimentPlan {
  if (!plan.exceedsStepLimit) return plan
  if (resolution === 'PRESERVE_DURATION') {
    const legalDt = plan.minimumTimeStepPreservingDurationSeconds
    if (legalDt == null) throw new Error('无法计算保持模拟时长所需的最小合法时间步长。')
    return planComparisonExperiment(plan.sourceConfig, legalDt)
  }
  const config: SimulationConfig = {
    ...plan.sourceConfig,
    timeStepSeconds: plan.proposedTimeStepSeconds,
    maxSteps: plan.sourceConfig.maxSteps == null ? null : MAX_ALLOWED_STEPS,
    targetSimulationTimeSeconds: plan.sourceConfig.targetSimulationTimeSeconds == null
      ? null
      : Math.min(
          plan.sourceConfig.targetSimulationTimeSeconds,
          MAX_ALLOWED_STEPS * plan.proposedTimeStepSeconds,
        ),
  }
  const steps = estimatedSteps(config)
  return {
    ...plan,
    proposedConfig: config,
    proposedMaxSteps: config.maxSteps ?? null,
    proposedEstimatedSteps: steps,
    proposedSimulationTimeSeconds: steps * config.timeStepSeconds,
    relativeStepCount: plan.originalEstimatedSteps > 0 ? steps / plan.originalEstimatedSteps : 1,
    exceedsStepLimit: false,
  }
}
