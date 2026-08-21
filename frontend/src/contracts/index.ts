/**
 * 契约类型的前端门面。
 *
 * 所有业务代码只从本文件导入契约类型，不直接引用 src/generated/*，
 * 这样契约字段调整时只需修改一处别名。
 * 契约中的数值一律为 SI 单位（kg、m、m/s、s、J）。
 */
import type { components } from '../generated/openapi'

type Schemas = components['schemas']

export type Vector3 = Schemas['Vector3']
export type BodySpec = Schemas['BodySpec']
export type SimulationConfig = Schemas['SimulationConfig']
export type Preset = Schemas['Preset']
export type PresetKey = Preset['key']
export type ValidationIssue = Schemas['ValidationIssue']
export type ValidationIssueCode = ValidationIssue['code']
export type RiskLevel = NonNullable<ValidationIssue['riskLevel']>
export type ValidationSeverity = ValidationIssue['severity']
export type ValidationResult = Schemas['ValidationResult']
export type ValidationGuidance = Schemas['ValidationGuidance']
export type GuidanceAction = Schemas['GuidanceAction']
export type GuidanceEvidence = Schemas['GuidanceEvidence']
export type ConfigSummary = Schemas['ConfigSummary']
export type ExperimentStatus = Schemas['ExperimentStatus']
export type EndReason = Schemas['EndReason']
export type ExperimentCreateRequest = Schemas['ExperimentCreateRequest']
export type ExperimentRetryContext = Schemas['ExperimentRetryContext']
export type ExperimentLineage = Schemas['ExperimentLineage']
export type Progress = Schemas['Progress']
export type BodyState = Schemas['BodyState']
export type SimulationState = Schemas['SimulationState']
export type Metrics = Schemas['Metrics']
export type SimulationEvent = Schemas['SimulationEvent']
export type SimulationEventType = SimulationEvent['type']
export type EventPhase = Schemas['EventPhase']
export type Diagnostic = Schemas['Diagnostic']
export type DiagnosticSeverity = Schemas['DiagnosticSeverity']
export type DiagnosticCauseCategory = Schemas['DiagnosticCauseCategory']
export type DiagnosticEvidence = Schemas['DiagnosticEvidence']
export type HistoryResponse = Schemas['HistoryResponse']
export type ReplayJob = Schemas['ReplayJob']
export type ReplayJobStatus = Schemas['ReplayJobStatus']
export type ReplaySource = NonNullable<Schemas['ReplaySource']>
export type ReplayJobCreateRequest = Schemas['ReplayJobCreateRequest']
export type TrajectoryInfo = Schemas['TrajectoryInfo']
export type SimulationHealthStatus = Schemas['SimulationHealthStatus']
export type DriftTrend = Schemas['DriftTrend']
export type HealthReason = Schemas['HealthReason']
export type HealthRecommendation = Schemas['HealthRecommendation']
export type SimulationHealthMetrics = Schemas['SimulationHealthMetrics']
export type SimulationHealthReport = Schemas['SimulationHealthReport']
export type ExperimentSummary = Schemas['ExperimentSummary']
export type Experiment = Schemas['Experiment']
export type ExperimentActionRequest = Schemas['ExperimentActionRequest']
export type ExperimentAction = ExperimentActionRequest['action']
export type QueueReorderRequest = Schemas['QueueReorderRequest']
export type DeleteResult = Schemas['DeleteResult']
export type ReportSamplePoint = Schemas['ReportSamplePoint']
export type ReportData = Schemas['ReportData']
export type ApiErrorBody = Schemas['ApiError']
export type ApiErrorCode = ApiErrorBody['code']

/** 实时画布每个天体保留的最近点数，与契约 trajectory.liveWindowSize 一致。 */
export const LIVE_TRAIL_LIMIT = 8000

/** 归档采样点上限，与契约 trajectory.pointLimit 一致。 */
export const ARCHIVE_POINT_LIMIT = 50000

/** 天体数量范围，与契约 SimulationConfig.bodies 的 minItems/maxItems 一致。 */
export const MIN_BODY_COUNT = 2
export const MAX_BODY_COUNT = 100

/** 终态集合：不再产生新的模拟进度。 */
export const TERMINAL_STATUSES: readonly ExperimentStatus[] = [
  'COMPLETED',
  'CANCELLED',
  'FAILED',
]

export function isTerminalStatus(status: ExperimentStatus): boolean {
  return TERMINAL_STATUSES.includes(status)
}

/** 仅 QUEUED 实验允许直接编辑配置，其余状态需要 RESTART。 */
export function isDirectlyEditable(status: ExperimentStatus): boolean {
  return status === 'QUEUED'
}

/**
 * 按契约状态机给出当前状态允许的动作集合。
 * 服务端仍是唯一权威，前端只用它来禁用按钮以避免明显的非法请求。
 */
export function allowedActions(status: ExperimentStatus): readonly ExperimentAction[] {
  switch (status) {
    case 'QUEUED':
      return ['RESTART', 'CANCEL']
    case 'RUNNING':
      return ['PAUSE', 'CANCEL']
    case 'PAUSED':
      return ['RESUME', 'STEP', 'RESTART', 'CANCEL']
    case 'COMPLETED':
    case 'CANCELLED':
    case 'FAILED':
      return ['RESTART']
    default:
      return []
  }
}

export function isActionAllowed(status: ExperimentStatus, action: ExperimentAction): boolean {
  return allowedActions(status).includes(action)
}

export const EXPERIMENT_STATUS_LABELS: Record<ExperimentStatus, string> = {
  QUEUED: '排队中',
  RUNNING: '运行中',
  PAUSED: '已暂停',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  FAILED: '已失败',
}

export const END_REASON_LABELS: Record<NonNullable<EndReason>, string> = {
  MAX_STEPS: '达到最大步数',
  TARGET_TIME: '达到目标模拟时间',
  CANCELLED: '用户取消',
  ERROR: '数值异常终止',
}

export const EVENT_TYPE_LABELS: Record<SimulationEventType, string> = {
  NEAR_ENCOUNTER: '近距离事件',
  STATUS_CHANGE: '状态变化',
  NUMERICAL_WARNING: '数值告警',
  DIAGNOSTIC: '运行时诊断',
  ERROR: '错误',
}
