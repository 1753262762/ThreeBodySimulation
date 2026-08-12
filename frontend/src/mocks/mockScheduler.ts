/**
 * Mock WebSocket 调度器。
 *
 * 每 120 ms 推进一次运行中的实验，并广播契约 1.1 信封消息。
 * 快照/轨迹/指标/状态/近距离事件/诊断/错误在不同序列号上发布，
 * 便于前端验证乱序丢弃、重连与全量恢复逻辑。
 *
 * 近距离事件按 ENTER/UPDATE/FINAL 生命周期发送，payload 为 { event }，
 * 与 1.1 契约一致；真实最近点与中点随 FINAL 输出。
 */
import type { SimulationConfig, Vector3 } from '../contracts'
import {
  advance,
  allRecords,
  emitEvent,
  getRecord,
  type MockRecord,
} from './mockRepository'
import { closestPair } from './mockEngine'

let timer: ReturnType<typeof setInterval> | null = null

interface WsClient {
  id: string
  send: (message: string) => void
}

const clients = new Map<string, WsClient>()

const SCHEMA_VERSION = '1.1'

function envelope(record: MockRecord, type: string, payload: unknown): string {
  record.wsSequence += 1
  return JSON.stringify({
    schemaVersion: SCHEMA_VERSION,
    type,
    experimentId: record.id,
    sequence: record.wsSequence,
    timestamp: new Date().toISOString(),
    payload,
  })
}

function publishSnapshot(record: MockRecord): void {
  const message = envelope(record, 'SNAPSHOT', {
    step: record.state.step,
    simulationTimeSeconds: record.state.simulationTimeSeconds,
    bodies: record.state.bodies.map((body) => ({
      id: body.id,
      position: body.position,
      velocity: body.velocity,
    })),
  })
  clients.get(record.id)?.send(message)
}

function publishTrajectory(record: MockRecord): void {
  const current = record.state
  const message = envelope(record, 'TRAJECTORY', {
    fromStep: Math.max(0, current.step - 1),
    toStep: current.step,
    stride: 1,
    points: [
      {
        step: current.step,
        simulationTimeSeconds: current.simulationTimeSeconds,
        bodies: current.bodies.map((body) => ({
          id: body.id,
          position: body.position,
          velocity: body.velocity,
        })),
      },
    ],
  })
  clients.get(record.id)?.send(message)
}

function publishMetrics(record: MockRecord): void {
  if (!record.metrics) return
  const message = envelope(record, 'METRICS', record.metrics)
  clients.get(record.id)?.send(message)
}

function publishStatus(record: MockRecord, message: string, endReason: MockRecord['endReason']): void {
  const payload = {
    status: record.status,
    previousStatus: null,
    step: record.state.step,
    simulationTimeSeconds: record.state.simulationTimeSeconds,
    endReason,
    completionRatio:
      record.config.maxSteps && record.config.maxSteps > 0
        ? record.state.step / record.config.maxSteps
        : null,
    queuePosition: 0,
    message,
  }
  const raw = envelope(record, 'STATUS', payload)
  clients.get(record.id)?.send(raw)
}

export function registerMockClient(experimentId: string, client: WsClient): void {
  clients.set(experimentId, client)
}

export function unregisterMockClient(experimentId: string): void {
  clients.delete(experimentId)
}

// ---- 近距离事件生命周期 ----

interface ActiveEncounter {
  eventId: string
  bodyIds: [string, string]
  thresholdMeters: number
  triggerDistanceMeters: number
  closestDistanceMeters: number
  closestStep: number
  closestSimulationTimeSeconds: number
  closestMidpoint: Vector3
}

const activeEncounters = new Map<string, ActiveEncounter>()

function encounterKey(recordId: string, bodyIds: string[]): string {
  return recordId + ':' + [...bodyIds].sort().join('|')
}

function bodyPosition(record: MockRecord, bodyId: string): Vector3 {
  const body = record.state.bodies.find((item) => item.id === bodyId)
  return body ? body.position : { x: 0, y: 0, z: 0 }
}

function midpointOf(record: MockRecord, bodyIds: string[]): Vector3 {
  const a = bodyPosition(record, bodyIds[0])
  const b = bodyPosition(record, bodyIds[1])
  return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2, z: (a.z + b.z) / 2 }
}

function encounterEventId(recordId: string, bodyIds: string[]): string {
  const hex = bodyIds.map((id) => id.slice(0, 8)).join('')
  return 'aaaaaaaa-0000-4000-8000-' + recordId.slice(0, 8) + hex.slice(0, 4)
}

function sendEncounter(
  record: MockRecord,
  encounter: ActiveEncounter,
  phase: 'ENTER' | 'UPDATE' | 'FINAL',
  currentDistance?: number,
): void {
  const isFinal = phase === 'FINAL'
  const step = isFinal ? encounter.closestStep : record.state.step
  const simTime = isFinal ? encounter.closestSimulationTimeSeconds : record.state.simulationTimeSeconds
  const distance = isFinal
    ? encounter.closestDistanceMeters
    : currentDistance ?? closestPair(record.state)?.distanceMeters ?? encounter.closestDistanceMeters
  const midpoint = isFinal ? encounter.closestMidpoint : midpointOf(record, encounter.bodyIds)
  const message =
    phase === 'ENTER'
      ? '天体距离低于阈值，进入近距离事件。'
      : phase === 'UPDATE'
        ? '近距离事件更新：记录到新的最近点。'
        : '天体脱离阈值范围，近距离事件结束。'
  const event = emitEvent(record, 'NEAR_ENCOUNTER', message, {
    eventId: encounter.eventId,
    phase,
    bodyIds: encounter.bodyIds,
    distanceMeters: isFinal ? encounter.closestDistanceMeters : distance,
    thresholdMeters: encounter.thresholdMeters,
    triggerDistanceMeters: encounter.triggerDistanceMeters,
    closestDistanceMeters: isFinal ? encounter.closestDistanceMeters : distance,
    closestStep: isFinal ? encounter.closestStep : step,
    closestSimulationTimeSeconds: isFinal ? encounter.closestSimulationTimeSeconds : simTime,
    midpointPosition: midpoint,
  })
  clients.get(record.id)?.send(envelope(record, 'NEAR_ENCOUNTER', { event }))
}

function processEncounters(record: MockRecord): void {
  const threshold = record.config.softeningLengthMeters * 5
  if (!(threshold > 0)) return
  const pair = closestPair(record.state)
  const ids: string[] | null = pair ? [pair.ids[0], pair.ids[1]] : null
  const inside = pair !== null && pair.distanceMeters < threshold

  // 先结束已脱离阈值范围的活跃事件。
  for (const [key, encounter] of activeEncounters) {
    if (!key.startsWith(record.id + ':')) continue
    const stillInside = inside && ids !== null && key === encounterKey(record.id, ids)
    if (!stillInside) {
      sendEncounter(record, encounter, 'FINAL')
      activeEncounters.delete(key)
    }
  }

  if (!pair || !ids || !inside) return
  const key = encounterKey(record.id, ids)
  const existing = activeEncounters.get(key)
  const currentMidpoint = midpointOf(record, ids)
  if (!existing) {
    const encounter: ActiveEncounter = {
      eventId: encounterEventId(record.id, ids),
      bodyIds: [ids[0], ids[1]],
      thresholdMeters: threshold,
      triggerDistanceMeters: threshold,
      closestDistanceMeters: pair.distanceMeters,
      closestStep: record.state.step,
      closestSimulationTimeSeconds: record.state.simulationTimeSeconds,
      closestMidpoint: currentMidpoint,
    }
    activeEncounters.set(key, encounter)
    sendEncounter(record, encounter, 'ENTER', pair.distanceMeters)
    return
  }
  // 只有出现更近点时发送 UPDATE 并更新真实最近点。
  if (pair.distanceMeters < existing.closestDistanceMeters) {
    existing.closestDistanceMeters = pair.distanceMeters
    existing.closestStep = record.state.step
    existing.closestSimulationTimeSeconds = record.state.simulationTimeSeconds
    existing.closestMidpoint = currentMidpoint
    sendEncounter(record, existing, 'UPDATE', pair.distanceMeters)
  }
}

// ---- 运行时诊断 ----

interface DiagnosticState {
  driftWarned: boolean
  driftCriticalWarned: boolean
}

const diagnosticState = new Map<string, DiagnosticState>()

function processDiagnostics(record: MockRecord): void {
  if (!record.metrics) return
  const drift = record.metrics.relativeEnergyDrift
  if (!Number.isFinite(drift)) return
  const state = diagnosticState.get(record.id) ?? { driftWarned: false, driftCriticalWarned: false }
  diagnosticState.set(record.id, state)
  const diagnostic = (() => {
    if (!state.driftCriticalWarned && Math.abs(drift) > 0.1) {
      state.driftCriticalWarned = true
      state.driftWarned = true
      return {
        code: 'ENERGY_DRIFT' as const,
        severity: 'CRITICAL' as const,
        causeCategory: 'NUMERICAL_ERROR' as const,
        summary: '系统能量漂移超过 10%，长期积分结果不可信。',
        likelyCauses: ['时间步长过大', '软化长度过小导致近接处数值误差'],
        evidence: {
          relativeEnergyDrift: drift,
          timeStepSeconds: record.config.timeStepSeconds,
          softeningLengthMeters: record.config.softeningLengthMeters,
          lastStableStep: record.state.step,
        },
        recommendations: ['减小时间步长', '增大软化长度后重新运行'],
      }
    }
    if (!state.driftWarned && Math.abs(drift) > 0.01) {
      state.driftWarned = true
      return {
        code: 'ENERGY_DRIFT' as const,
        severity: 'WARNING' as const,
        causeCategory: 'NUMERICAL_ERROR' as const,
        summary: '系统能量漂移超过 1%，建议检查时间步长与软化长度。',
        likelyCauses: ['时间步长相对轨道周期偏大'],
        evidence: {
          relativeEnergyDrift: drift,
          timeStepSeconds: record.config.timeStepSeconds,
          softeningLengthMeters: record.config.softeningLengthMeters,
          lastStableStep: record.state.step,
        },
        recommendations: ['减小时间步长以降低漂移', '如为展示用途可继续观察'],
      }
    }
    return null
  })()
  if (!diagnostic) return
  const event = emitEvent(record, 'DIAGNOSTIC', diagnostic.summary, {
    eventId: 'bbbbbbbb-0000-4000-8000-' + (diagnostic.severity === 'CRITICAL' ? '00000001' : '00000002'),
    phase: 'FINAL',
    bodyIds: null,
    diagnostic,
  })
  clients.get(record.id)?.send(envelope(record, 'DIAGNOSTIC', { event }))
}

/** 每 tick 推进一次运行中的实验，并广播三类增量消息。 */
export function promoteQueuedHead(): void {
  const hasRunning = allRecords().some((record) => record.status === 'RUNNING')
  if (hasRunning) return
  const next = allRecords()
    .filter((record) => record.status === 'QUEUED')
    .sort((a, b) => a.queueOrder - b.queueOrder)[0]
  if (!next) return
  next.status = 'RUNNING'
  if (!next.startedAt) next.startedAt = new Date().toISOString()
  publishStatus(next, '开始运行。', null)
}

export function advanceMockOneTick(): void {
  promoteQueuedHead()
  for (const record of allRecords()) {
    if (record.status !== 'RUNNING') continue
    const outcome = advance(record, 1)
    publishSnapshot(record)
    publishTrajectory(record)
    publishMetrics(record)
    processEncounters(record)
    processDiagnostics(record)
    if (outcome.finished === 'MAX_STEPS') {
      publishStatus(record, '达到最大步数，实验完成。', 'MAX_STEPS')
    } else if (outcome.finished === 'TARGET_TIME') {
      publishStatus(record, '达到目标模拟时间，实验完成。', 'TARGET_TIME')
    } else if (outcome.failure) {
      publishStatus(record, outcome.failure, 'ERROR')
      const message = envelope(record, 'ERROR', {
        code: 'NUMERICAL_INSTABILITY',
        message: outcome.failure,
        step: record.state.step,
        recoverable: false,
      })
      clients.get(record.id)?.send(message)
    }
  }
}

/** 启动调度器；没有运行中实验时自动停止。 */
export function startMockScheduler(): void {
  if (timer) return
  timer = setInterval(() => {
    advanceMockOneTick()
    const hasRunning = allRecords().some((record) => record.status === 'RUNNING')
    if (!hasRunning && timer) {
      clearInterval(timer)
      timer = null
    }
  }, 120)
}

export function stopMockScheduler(): void {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

export function isMockSchedulerRunning(): boolean {
  return timer !== null
}

export function debugPickRunningRecord(): string | null {
  const running = allRecords().find((record) => record.status === 'RUNNING')
  return running?.id ?? null
}

export function getDebugRecord(id: string): MockRecord | null {
  return getRecord(id) ?? null
}

export function getDebugConfig(id: string): SimulationConfig | null {
  return getRecord(id)?.config ?? null
}

export function getDebugClosestPair(id: string) {
  const record = getRecord(id)
  return record ? closestPair(record.state) : null
}

/** 测试辅助：清空生命周期状态。 */
export function resetSchedulerState(): void {
  activeEncounters.clear()
  diagnosticState.clear()
}
