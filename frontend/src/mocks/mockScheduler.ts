/**
 * Mock WebSocket 调度器。
 *
 * 每 120 ms 推进一次运行中的实验，并广播契约信封消息。
 * 快照/轨迹/指标/状态/近距离事件/错误在不同序列号上发布，
 * 便于前端验证乱序丢弃、重连与全量恢复逻辑。
 */
import type { SimulationConfig } from '../contracts'
import { advance, allRecords, getRecord, type MockRecord } from './mockRepository'
import { closestPair } from './mockEngine'

let timer: ReturnType<typeof setInterval> | null = null

interface WsClient {
  id: string
  send: (message: string) => void
}

const clients = new Map<string, WsClient>()

function envelope(record: MockRecord, type: string, payload: unknown): string {
  record.wsSequence += 1
  return JSON.stringify({
    schemaVersion: '1.0',
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
    for (const encounter of outcome.nearEncounters) {
      const message = envelope(record, 'NEAR_ENCOUNTER', {
        step: record.state.step,
        simulationTimeSeconds: record.state.simulationTimeSeconds,
        bodyIds: encounter.bodyIds,
        distanceMeters: encounter.distanceMeters,
        thresholdMeters: encounter.thresholdMeters,
        message: '天体距离低于 5 倍软化长度。',
      })
      clients.get(record.id)?.send(message)
    }
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