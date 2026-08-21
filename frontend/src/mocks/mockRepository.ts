/**
 * Mock 实验仓库。
 *
 * 用单工作线程语义模拟后端：同一时刻最多一个 RUNNING 实验，
 * 队列顺序消费，并按契约维护状态机、指标、事件与采样。
 */
import {
  type Diagnostic,
  type EndReason,
  type EventPhase,
  type Experiment,
  type ExperimentStatus,
  type ExperimentLineage,
  type ExperimentSummary,
  type HistoryResponse,
  type Metrics,
  type Preset,
  type ReportSamplePoint,
  type ReplayJobStatus,
  type ReplaySource,
  type SimulationConfig,
  type SimulationEvent,
  type SimulationState,
  type SimulationHealthReport,
  type Vector3,
} from '../contracts'
import { closestPair, computeMetrics, rk4Step, type MockBody, type MockState } from './mockEngine'
import presetsFixture from '../../../contracts/examples/presets.json'
import { failMockHealth, updateMockHealth } from './mockHealth'

const ARCHIVE_LIMIT = 50000
const LIVE_WINDOW = 8000
/** 重算任务单 tick 最大推进步数，避免长时间阻塞主线程。 */
const REPLAY_STEPS_PER_TICK = 5000
/** 重算结果在内存中的保留时间，与后端一致为 10 分钟。 */
const REPLAY_RETENTION_MS = 10 * 60 * 1000

export const mockPresets = presetsFixture as unknown as Preset[]

/** Mock 回放任务。transient 字段 _state 为执行期内部状态，不对外暴露。 */
export interface MockReplayJob {
  jobId: string
  experimentId: string
  targetStep: number
  status: ReplayJobStatus
  source: ReplaySource | null
  baseStep: number | null
  completedSteps: number
  totalSteps: number
  progress: number
  result: SimulationState | null
  error: string | null
  createdAt: string
  updatedAt: string
  expiresAt: string
  _state: MockState | null
}

interface MockRecord {
  id: string
  name: string
  status: ExperimentStatus
  config: SimulationConfig
  state: MockState
  metrics: Metrics | null
  initialMetrics: Metrics | null
  healthReport: SimulationHealthReport | null
  lineage: ExperimentLineage | null
  initialTotalEnergy: number | null
  allTimeMinimum: { distanceMeters: number; step: number } | null
  events: SimulationEvent[]
  samples: ReportSamplePoint[]
  sampleStride: number
  eventSequence: number
  wsSequence: number
  replayJobs: Map<string, MockReplayJob>
  createdAt: string
  updatedAt: string
  startedAt: string | null
  completedAt: string | null
  endReason: EndReason
  errorCode: string | null
  errorMessage: string | null
  queueOrder: number
  wallClockSeconds: number
}

let idCounter = 0
let jobCounter = 0
let queueCounter = 0
const records = new Map<string, MockRecord>()
let replayTimer: ReturnType<typeof setInterval> | null = null

function nextId(): string {
  idCounter += 1
  const hex = idCounter.toString(16).padStart(12, '0')
  return '00000000-0000-4000-8000-' + hex
}

function nextJobId(): string {
  jobCounter += 1
  const hex = jobCounter.toString(16).padStart(12, '0')
  return '10000000-0000-4000-8000-' + hex
}

function nowIso(): string {
  return new Date().toISOString()
}

function toMockBodies(config: SimulationConfig): MockBody[] {
  return config.bodies.map((body, index) => ({
    id: body.id ?? (index + 1).toString().padStart(8, '0') + '-0000-4000-8000-000000000000',
    name: body.name,
    color: body.color ?? '#ffc857',
    massKg: body.massKg,
    position: { ...body.position },
    velocity: { ...body.velocity },
  }))
}

/** 补齐天体 ID 与颜色，模拟服务端的 normalizedConfig 行为。 */
export function normalizeConfig(config: SimulationConfig): SimulationConfig {
  const palette = ['#ffc857', '#59c3ff', '#ff647c', '#c084fc', '#4ade80', '#fb923c']
  return {
    ...config,
    bodies: config.bodies.map((body, index) => ({
      ...body,
      id: body.id ?? (index + 1).toString().padStart(8, '0') + '-0000-4000-8000-000000000000',
      color: body.color ?? palette[index % palette.length],
    })),
  }
}

export function createRecord(config: SimulationConfig, name?: string,
  lineage: ExperimentLineage | null = null): MockRecord {
  const normalized = normalizeConfig(config)
  const id = nextId()
  queueCounter += 1
  const record: MockRecord = {
    id,
    name: name ?? normalized.name ?? '实验 ' + idCounter,
    status: 'QUEUED',
    config: normalized,
    state: {
      step: 0,
      simulationTimeSeconds: 0,
      bodies: toMockBodies(normalized),
    },
    metrics: null,
    initialMetrics: null,
    healthReport: null,
    lineage,
    initialTotalEnergy: null,
    allTimeMinimum: null,
    events: [],
    samples: [],
    sampleStride: 1,
    eventSequence: 0,
    wsSequence: 0,
    replayJobs: new Map(),
    createdAt: nowIso(),
    updatedAt: nowIso(),
    startedAt: null,
    completedAt: null,
    endReason: null,
    errorCode: null,
    errorMessage: null,
    queueOrder: queueCounter,
    wallClockSeconds: 0,
  }
  const metrics = computeMetrics(record.state, normalized, null, null, null, 0)
  record.initialTotalEnergy = metrics.totalEnergyJoules
  record.metrics = metrics
  record.initialMetrics = metrics
  record.healthReport = updateMockHealth(normalized, metrics, metrics, null, 0, 0)
  recordSample(record)
  records.set(id, record)
  return record
}

function canonicalConfig(config: SimulationConfig): string {
  const normalized = normalizeConfig(config)
  return JSON.stringify({
    ...normalized,
    name: undefined,
    bodies: normalized.bodies.map((body) => ({
      name: body.name,
      color: body.color,
      massKg: body.massKg,
      position: body.position,
      velocity: body.velocity,
    })),
  })
}

function duplicatePriority(status: ExperimentStatus): number {
  if (status === 'RUNNING' || status === 'QUEUED' || status === 'PAUSED') return 0
  if (status === 'COMPLETED') return 1
  return 2
}

export function findEquivalentRecord(config: SimulationConfig): MockRecord | undefined {
  const target = canonicalConfig(config)
  return allRecords()
    .filter((record) => canonicalConfig(record.config) === target)
    .sort((a, b) => {
      const priority = duplicatePriority(a.status) - duplicatePriority(b.status)
      return priority !== 0 ? priority : Date.parse(b.updatedAt) - Date.parse(a.updatedAt)
    })[0]
}

/** 原地重置，保持与真实后端 RESTART 的同 ID 语义一致。 */
export function restartRecord(record: MockRecord, config: SimulationConfig): void {
  const normalized = normalizeConfig(config)
  const state: MockState = {
    step: 0,
    simulationTimeSeconds: 0,
    bodies: toMockBodies(normalized),
  }
  const metrics = computeMetrics(state, normalized, null, null, null, 0)
  record.status = 'QUEUED'
  record.config = normalized
  record.state = state
  record.metrics = metrics
  record.initialMetrics = metrics
  record.healthReport = updateMockHealth(normalized, metrics, metrics, null, 0, 0)
  record.initialTotalEnergy = metrics.totalEnergyJoules
  record.allTimeMinimum = null
  record.events = []
  record.samples = []
  record.sampleStride = 1
  record.eventSequence = 0
  record.wsSequence = 0
  record.replayJobs.clear()
  record.updatedAt = nowIso()
  record.startedAt = null
  record.completedAt = null
  record.endReason = null
  record.errorCode = null
  record.errorMessage = null
  record.wallClockSeconds = 0
  recordSample(record)
}

function recordSample(record: MockRecord): void {
  const point: ReportSamplePoint = {
    step: record.state.step,
    simulationTimeSeconds: record.state.simulationTimeSeconds,
    bodies: record.state.bodies.map((body) => ({
      id: body.id,
      position: { ...body.position },
      velocity: { ...body.velocity },
    })),
    totalEnergyJoules: record.metrics?.totalEnergyJoules ?? null,
    relativeEnergyDrift: record.metrics?.relativeEnergyDrift ?? null,
    angularMomentumMagnitude: record.metrics?.angularMomentumMagnitude ?? null,
    minimumPairDistanceMeters: record.metrics?.minimumPairDistanceMeters ?? null,
  }
  record.samples.push(point)
  // 达到上限后保留首尾并加倍步长，与后端分层采样策略一致。
  if (record.samples.length > ARCHIVE_LIMIT) {
    const kept: ReportSamplePoint[] = []
    for (let i = 0; i < record.samples.length; i += 2) {
      kept.push(record.samples[i])
    }
    const last = record.samples[record.samples.length - 1]
    if (kept[kept.length - 1]?.step !== last.step) kept.push(last)
    record.samples = kept
    record.sampleStride *= 2
  }
}

export interface AddEventOptions {
  eventId?: string | null
  phase?: EventPhase | null
  bodyIds?: string[] | null
  distanceMeters?: number | null
  thresholdMeters?: number | null
  triggerDistanceMeters?: number | null
  closestDistanceMeters?: number | null
  closestStep?: number | null
  closestSimulationTimeSeconds?: number | null
  midpointPosition?: Vector3 | null
  diagnostic?: Diagnostic | null
}

export function addEvent(
  record: MockRecord,
  type: SimulationEvent['type'],
  message: string,
  options: AddEventOptions = {},
): SimulationEvent {
  record.eventSequence += 1
  const event: SimulationEvent = {
    sequence: record.eventSequence,
    eventId: options.eventId ?? undefined,
    type,
    phase: options.phase ?? undefined,
    step: record.state.step,
    simulationTimeSeconds: record.state.simulationTimeSeconds,
    timestamp: nowIso(),
    message,
    bodyIds: options.bodyIds ?? null,
    distanceMeters: options.distanceMeters ?? null,
    thresholdMeters: options.thresholdMeters ?? null,
    triggerDistanceMeters: options.triggerDistanceMeters ?? null,
    closestDistanceMeters: options.closestDistanceMeters ?? null,
    closestStep: options.closestStep ?? null,
    closestSimulationTimeSeconds: options.closestSimulationTimeSeconds ?? null,
    midpointPosition: options.midpointPosition ?? undefined,
    diagnostic: options.diagnostic ?? undefined,
  }
  record.events.push(event)
  return event
}

/** 按 eventId 合并事件：存在则原位替换并保留创建顺序，同时移除同 ID 的旧副本。 */
export function upsertEvent(record: MockRecord, event: SimulationEvent): SimulationEvent {
  if (event.eventId) {
    const index = record.events.findIndex((item) => item.eventId === event.eventId)
    if (index >= 0) {
      const merged = { ...event, sequence: record.events[index].sequence }
      record.events[index] = merged
      for (let i = record.events.length - 1; i > index; i -= 1) {
        if (record.events[i].eventId === event.eventId) {
          record.events.splice(i, 1)
        }
      }
      return merged
    }
  }
  record.events.push(event)
  return event
}

/**
 * 带稳定 eventId 的事件发布：同一 eventId 的修订原地替换（sequence 保持创建值），
 * 新事件才分配新 sequence。用于 NEAR_ENCOUNTER / DIAGNOSTIC 生命周期。
 */
export function emitEvent(
  record: MockRecord,
  type: SimulationEvent['type'],
  message: string,
  options: AddEventOptions = {},
): SimulationEvent {
  return upsertEvent(record, addEvent(record, type, message, options))
}

function toSimulationState(state: MockState): SimulationState {
  return {
    step: state.step,
    simulationTimeSeconds: state.simulationTimeSeconds,
    bodies: state.bodies.map((body) => ({
      id: body.id,
      position: { ...body.position },
      velocity: { ...body.velocity },
    })),
  }
}

function mockStateFromSimulation(state: SimulationState, config: SimulationConfig): MockState {
  const byId = new Map(state.bodies.map((body) => [body.id, body]))
  return {
    step: state.step,
    simulationTimeSeconds: state.simulationTimeSeconds,
    bodies: config.bodies.map((body) => {
      const current = byId.get(body.id ?? '')
      return {
        id: body.id ?? '',
        name: body.name,
        color: body.color ?? '#ffc857',
        massKg: body.massKg,
        position: current ? { ...current.position } : { ...body.position },
        velocity: current ? { ...current.velocity } : { ...body.velocity },
      }
    }),
  }
}

/** 历史范围查询：返回落在 [fromStep, toStep] 的归档点，超过 maxPoints 时均匀抽样保留首尾。 */
export function getHistorySlice(
  record: MockRecord,
  fromStep: number,
  toStep: number,
  maxPoints: number,
): HistoryResponse {
  const points = record.samples.filter((sample) => sample.step >= fromStep && sample.step <= toStep)
  let selected: ReportSamplePoint[] = points
  if (points.length > maxPoints && points.length >= 2) {
    selected = [points[0]]
    const stride = points.length / maxPoints
    for (let i = 1; i < maxPoints - 1; i += 1) {
      selected.push(points[Math.floor(i * stride)])
    }
    selected.push(points[points.length - 1])
  }
  const statePoints: SimulationState[] = selected.map((point) => ({
    step: point.step,
    simulationTimeSeconds: point.simulationTimeSeconds,
    bodies: point.bodies.map((body) => ({
      id: body.id,
      position: { ...body.position },
      velocity: { ...body.velocity },
    })),
  }))
  const availableFromStep = points.length > 0 ? points[0].step : null
  const availableToStep = points.length > 0 ? points[points.length - 1].step : null
  return {
    points: statePoints,
    availableFromStep,
    availableToStep,
    archiveSampleStride: record.sampleStride,
    downsampled: points.length > maxPoints,
    currentState: toSimulationState(record.state),
  }
}

export function findTrajectoryAt(record: MockRecord, step: number): SimulationState | null {
  const sample = record.samples.find((item) => item.step === step)
  if (!sample) return null
  return {
    step: sample.step,
    simulationTimeSeconds: sample.simulationTimeSeconds,
    bodies: sample.bodies.map((body) => ({
      id: body.id,
      position: { ...body.position },
      velocity: { ...body.velocity },
    })),
  }
}

export function findTrajectoryAtOrBefore(record: MockRecord, step: number): SimulationState | null {
  let best: SimulationState | null = null
  for (const sample of record.samples) {
    if (sample.step > step) break
    best = {
      step: sample.step,
      simulationTimeSeconds: sample.simulationTimeSeconds,
      bodies: sample.bodies.map((body) => ({
        id: body.id,
        position: { ...body.position },
        velocity: { ...body.velocity },
      })),
    }
  }
  return best
}

// ---- 回放任务 ----

export function pendingReplayJobCount(): number {
  let count = 0
  for (const record of records.values()) {
    for (const job of record.replayJobs.values()) {
      if (job.status === 'QUEUED' || job.status === 'RUNNING') count += 1
    }
  }
  return count
}

function makeJob(
  record: MockRecord,
  targetStep: number,
  status: ReplayJobStatus,
  source: ReplaySource | null,
  result: SimulationState | null,
  baseStep: number | null,
  completedSteps: number,
  progress: number,
): MockReplayJob {
  const jobId = nextJobId()
  return {
    jobId,
    experimentId: record.id,
    targetStep,
    status,
    source,
    baseStep,
    completedSteps,
    totalSteps: Math.max(1, targetStep - (baseStep ?? 0)),
    progress,
    result,
    error: null,
    createdAt: nowIso(),
    updatedAt: nowIso(),
    expiresAt: new Date(Date.now() + REPLAY_RETENTION_MS).toISOString(),
    _state: null,
  }
}

/** 创建回放任务；返回 HTTP 语义需要的状态码 200（已完成）或 202（已排队重算）。 */
export function createReplayJob(
  record: MockRecord,
  targetStep: number,
): { job: MockReplayJob; httpStatus: 200 | 202 } {
  const current = toSimulationState(record.state)
  if (targetStep === record.state.step) {
    const job = makeJob(record, targetStep, 'COMPLETED', 'CURRENT_STATE', current, targetStep, 0, 1)
    record.replayJobs.set(job.jobId, job)
    return { job, httpStatus: 200 }
  }
  const exact = findTrajectoryAt(record, targetStep)
  if (exact) {
    const job = makeJob(record, targetStep, 'COMPLETED', 'ARCHIVE_EXACT', exact, targetStep, 0, 1)
    record.replayJobs.set(job.jobId, job)
    return { job, httpStatus: 200 }
  }
  const floor = findTrajectoryAtOrBefore(record, targetStep)
  const baseStep = floor?.step ?? 0
  const totalSteps = Math.max(1, targetStep - baseStep)
  const job = makeJob(record, targetStep, 'QUEUED', 'RECOMPUTED', null, baseStep, 0, 0)
  job.totalSteps = totalSteps
  job._state = floor ? mockStateFromSimulation(floor, record.config) : null
  record.replayJobs.set(job.jobId, job)
  return { job, httpStatus: 202 }
}

export function getReplayJob(record: MockRecord, jobId: string): MockReplayJob | null {
  return record.replayJobs.get(jobId) ?? null
}

/** 删除回放任务：运行中任务进入 CANCELLED，已终态任务保持原终态。 */
export function deleteReplayJob(record: MockRecord, jobId: string): boolean {
  const job = record.replayJobs.get(jobId)
  if (!job) return false
  if (job.status === 'QUEUED' || job.status === 'RUNNING') {
    job.status = 'CANCELLED'
    job.updatedAt = nowIso()
    job.expiresAt = new Date(Date.now() + REPLAY_RETENTION_MS).toISOString()
  }
  return true
}

export function replayJobResponse(job: MockReplayJob) {
  return {
    jobId: job.jobId,
    experimentId: job.experimentId,
    targetStep: job.targetStep,
    status: job.status,
    source: job.source,
    baseStep: job.baseStep,
    completedSteps: job.completedSteps,
    totalSteps: job.totalSteps,
    progress: job.progress,
    result: job.result,
    error: job.error,
    createdAt: job.createdAt,
    updatedAt: job.updatedAt,
    expiresAt: job.expiresAt,
  }
}

function tickReplayJob(record: MockRecord, job: MockReplayJob): void {
  if (job.status === 'QUEUED') {
    job.status = 'RUNNING'
    job.updatedAt = nowIso()
    if (!job._state) {
      const floor = findTrajectoryAtOrBefore(record, job.baseStep ?? 0)
      job._state = floor ? mockStateFromSimulation(floor, record.config) : null
    }
    if (!job._state) {
      job._state = {
        step: 0,
        simulationTimeSeconds: 0,
        bodies: toMockBodies(record.config),
      }
    }
  }
  const target = job.targetStep
  let advanced = 0
  while (job._state && job._state.step < target && advanced < REPLAY_STEPS_PER_TICK) {
    job._state = rk4Step(job._state, record.config)
    advanced += 1
  }
  if (job._state) {
    job.completedSteps = job._state.step - (job.baseStep ?? 0)
    job.progress = job.totalSteps > 0 ? Math.min(1, job.completedSteps / job.totalSteps) : 1
  }
  job.updatedAt = nowIso()
  if (job._state && job._state.step >= target) {
    job.status = 'COMPLETED'
    job.progress = 1
    job.result = toSimulationState(job._state)
    job.updatedAt = nowIso()
    job.expiresAt = new Date(Date.now() + REPLAY_RETENTION_MS).toISOString()
  }
}

function cleanupExpiredJobs(): void {
  const now = Date.now()
  for (const record of records.values()) {
    for (const [jobId, job] of record.replayJobs) {
      const terminal = job.status === 'COMPLETED' || job.status === 'CANCELLED' || job.status === 'FAILED'
      if (terminal && new Date(job.expiresAt).getTime() <= now) {
        record.replayJobs.delete(jobId)
      }
    }
  }
}

/** 推进所有回放任务；没有待处理任务时自动停止定时器。 */
export function scheduleReplayJobs(): void {
  if (replayTimer) return
  replayTimer = setInterval(() => {
    let busy = false
    cleanupExpiredJobs()
    for (const record of records.values()) {
      for (const job of record.replayJobs.values()) {
        if (job.status === 'QUEUED' || job.status === 'RUNNING') {
          busy = true
          tickReplayJob(record, job)
        }
      }
    }
    if (!busy && replayTimer) {
      clearInterval(replayTimer)
      replayTimer = null
    }
  }, 20)
}

export function stopReplayJobs(): void {
  if (replayTimer) {
    clearInterval(replayTimer)
    replayTimer = null
  }
}

function endConditionReached(record: MockRecord): EndReason {
  const { maxSteps, targetSimulationTimeSeconds } = record.config
  if (maxSteps !== null && maxSteps !== undefined && record.state.step >= maxSteps) {
    return 'MAX_STEPS'
  }
  if (
    targetSimulationTimeSeconds !== null &&
    targetSimulationTimeSeconds !== undefined &&
    record.state.simulationTimeSeconds >= targetSimulationTimeSeconds
  ) {
    return 'TARGET_TIME'
  }
  return null
}

export interface AdvanceOutcome {
  nearEncounters: { bodyIds: string[]; distanceMeters: number; thresholdMeters: number }[]
  finished: EndReason
  failure: string | null
}

/** 推进指定步数，返回期间产生的近距离事件与结束原因。 */
export function advance(record: MockRecord, steps: number): AdvanceOutcome {
  const outcome: AdvanceOutcome = { nearEncounters: [], finished: null, failure: null }
  const threshold = record.config.softeningLengthMeters * 5
  const sampleInterval = Math.max(1, record.sampleStride)

  for (let i = 0; i < steps; i += 1) {
    record.state = rk4Step(record.state, record.config)
    record.wallClockSeconds += record.config.timeStepSeconds / 1e6

    const hasNonFinite = record.state.bodies.some(
      (body) =>
        !Number.isFinite(body.position.x) ||
        !Number.isFinite(body.position.y) ||
        !Number.isFinite(body.position.z) ||
        !Number.isFinite(body.velocity.x) ||
        !Number.isFinite(body.velocity.y) ||
        !Number.isFinite(body.velocity.z),
    )
    if (hasNonFinite) {
      outcome.failure = '积分过程中出现非有限数值。'
      record.status = 'FAILED'
      record.endReason = 'ERROR'
      record.errorCode = 'NUMERICAL_INSTABILITY'
      record.errorMessage = outcome.failure
      record.completedAt = nowIso()
      record.healthReport = failMockHealth(record.healthReport, record.state.step,
        record.state.simulationTimeSeconds, outcome.failure)
      addEvent(record, 'ERROR', outcome.failure)
      return outcome
    }

    const pair = closestPair(record.state)
    if (pair) {
      if (!record.allTimeMinimum || pair.distanceMeters < record.allTimeMinimum.distanceMeters) {
        record.allTimeMinimum = { distanceMeters: pair.distanceMeters, step: record.state.step }
      }
      if (pair.distanceMeters < threshold) {
        outcome.nearEncounters.push({
          bodyIds: [pair.ids[0], pair.ids[1]],
          distanceMeters: pair.distanceMeters,
          thresholdMeters: threshold,
        })
      }
    }

    record.metrics = computeMetrics(
      record.state,
      record.config,
      record.initialTotalEnergy,
      record.allTimeMinimum,
      1 / Math.max(1e-6, record.config.timeStepSeconds / 1e6),
      record.wallClockSeconds,
    )
    if (record.initialMetrics) {
      record.healthReport = updateMockHealth(record.config, record.initialMetrics, record.metrics,
        record.healthReport, record.state.step, record.state.simulationTimeSeconds)
    }

    if (record.state.step % sampleInterval === 0) {
      recordSample(record)
    }

    const finished = endConditionReached(record)
    if (finished) {
      outcome.finished = finished
      record.status = 'COMPLETED'
      record.endReason = finished
      record.completedAt = nowIso()
      recordSample(record)
      addEvent(record, 'STATUS_CHANGE', finished === 'MAX_STEPS' ? '达到最大步数，实验完成。' : '达到目标模拟时间，实验完成。')
      return outcome
    }
  }
  record.updatedAt = nowIso()
  return outcome
}

function completionRatio(record: MockRecord): number | null {
  const { maxSteps, targetSimulationTimeSeconds } = record.config
  const ratios: number[] = []
  if (maxSteps) ratios.push(record.state.step / maxSteps)
  if (targetSimulationTimeSeconds) {
    ratios.push(record.state.simulationTimeSeconds / targetSimulationTimeSeconds)
  }
  if (ratios.length === 0) return null
  return Math.min(1, Math.max(...ratios))
}

export function toSummary(record: MockRecord): ExperimentSummary {
  return {
    id: record.id,
    name: record.name,
    status: record.status,
    queuePosition: queuePosition(record),
    createdAt: record.createdAt,
    updatedAt: record.updatedAt,
    startedAt: record.startedAt,
    completedAt: record.completedAt,
    bodyCount: record.config.bodies.length,
    progress: {
      step: record.state.step,
      simulationTimeSeconds: record.state.simulationTimeSeconds,
      maxSteps: record.config.maxSteps ?? null,
      targetSimulationTimeSeconds: record.config.targetSimulationTimeSeconds ?? null,
      completionRatio: completionRatio(record),
      stepsPerSecond: record.metrics?.stepsPerSecond ?? null,
      estimatedRemainingSteps: record.config.maxSteps
        ? Math.max(0, record.config.maxSteps - record.state.step)
        : null,
    },
    endReason: record.endReason,
    storageBytes: record.samples.length * 320,
    errorCode: record.errorCode,
    healthStatus: record.healthReport?.status ?? null,
    lineage: record.lineage,
  }
}

export function toExperiment(record: MockRecord): Experiment {
  return {
    ...toSummary(record),
    config: record.config,
    state: {
      step: record.state.step,
      simulationTimeSeconds: record.state.simulationTimeSeconds,
      bodies: record.state.bodies.map((body) => ({
        id: body.id,
        position: { ...body.position },
        velocity: { ...body.velocity },
      })),
    },
    metrics: record.metrics,
    healthReport: record.healthReport,
    lineage: record.lineage,
    trajectory: {
      sampleStride: record.sampleStride,
      sampleCount: record.samples.length,
      pointLimit: ARCHIVE_LIMIT,
      liveWindowSize: LIVE_WINDOW,
    },
    events: record.events,
    lastSequence: record.wsSequence,
    errorMessage: record.errorMessage,
  }
}

function queuePosition(record: MockRecord): number {
  const ordered = allRecords()
    .filter((item) => item.status === 'QUEUED' || item.status === 'RUNNING')
    .sort((a, b) => a.queueOrder - b.queueOrder)
  const index = ordered.findIndex((item) => item.id === record.id)
  return index < 0 ? 0 : index
}

export function allRecords(): MockRecord[] {
  return [...records.values()]
}

export function getRecord(id: string): MockRecord | undefined {
  return records.get(id)
}

export function deleteRecord(id: string): number {
  const record = records.get(id)
  if (!record) return 0
  const freed = record.samples.length * 320
  records.delete(id)
  return freed
}

export function reorder(orderedIds: string[]): void {
  orderedIds.forEach((id, index) => {
    const record = records.get(id)
    if (record) record.queueOrder = index + 1
  })
  queueCounter = Math.max(queueCounter, orderedIds.length)
}

export function resetRepository(): void {
  stopReplayJobs()
  records.clear()
  idCounter = 0
  jobCounter = 0
  queueCounter = 0
}

export type { MockRecord }
