/**
 * Mock 实验仓库。
 *
 * 用单工作线程语义模拟后端：同一时刻最多一个 RUNNING 实验，
 * 队列顺序消费，并按契约维护状态机、指标、事件与采样。
 */
import {
  type EndReason,
  type Experiment,
  type ExperimentStatus,
  type ExperimentSummary,
  type Metrics,
  type Preset,
  type ReportSamplePoint,
  type SimulationConfig,
  type SimulationEvent,
} from '../contracts'
import { closestPair, computeMetrics, rk4Step, type MockBody, type MockState } from './mockEngine'
import presetsFixture from '../../../contracts/examples/presets.json'

const ARCHIVE_LIMIT = 50000
const LIVE_WINDOW = 2000

export const mockPresets = presetsFixture as unknown as Preset[]

interface MockRecord {
  id: string
  name: string
  status: ExperimentStatus
  config: SimulationConfig
  state: MockState
  metrics: Metrics | null
  initialTotalEnergy: number | null
  allTimeMinimum: { distanceMeters: number; step: number } | null
  events: SimulationEvent[]
  samples: ReportSamplePoint[]
  sampleStride: number
  eventSequence: number
  wsSequence: number
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
let queueCounter = 0
const records = new Map<string, MockRecord>()

function nextId(): string {
  idCounter += 1
  const hex = idCounter.toString(16).padStart(12, '0')
  return `00000000-0000-4000-8000-${hex}`
}

function nowIso(): string {
  return new Date().toISOString()
}

function toMockBodies(config: SimulationConfig): MockBody[] {
  return config.bodies.map((body, index) => ({
    id: body.id ?? `${index + 1}`.padStart(8, '0') + '-0000-4000-8000-000000000000',
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
      id: body.id ?? `${(index + 1).toString().padStart(8, '0')}-0000-4000-8000-000000000000`,
      color: body.color ?? palette[index % palette.length],
    })),
  }
}

export function createRecord(config: SimulationConfig, name?: string): MockRecord {
  const normalized = normalizeConfig(config)
  const id = nextId()
  queueCounter += 1
  const record: MockRecord = {
    id,
    name: name ?? normalized.name ?? `实验 ${idCounter}`,
    status: 'QUEUED',
    config: normalized,
    state: {
      step: 0,
      simulationTimeSeconds: 0,
      bodies: toMockBodies(normalized),
    },
    metrics: null,
    initialTotalEnergy: null,
    allTimeMinimum: null,
    events: [],
    samples: [],
    sampleStride: 1,
    eventSequence: 0,
    wsSequence: 0,
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
  recordSample(record)
  records.set(id, record)
  return record
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

export function addEvent(
  record: MockRecord,
  type: SimulationEvent['type'],
  message: string,
  bodyIds: string[] | null = null,
  distanceMeters: number | null = null,
): SimulationEvent {
  record.eventSequence += 1
  const event: SimulationEvent = {
    sequence: record.eventSequence,
    type,
    step: record.state.step,
    simulationTimeSeconds: record.state.simulationTimeSeconds,
    timestamp: nowIso(),
    message,
    bodyIds,
    distanceMeters,
  }
  record.events.push(event)
  return event
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
  records.clear()
  idCounter = 0
  queueCounter = 0
}

export type { MockRecord }