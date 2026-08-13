import { describe, expect, it, vi } from 'vitest'
import { ExperimentSocket, snapshotToState, type SnapshotPayload } from '../experimentSocket'

class FakeSocket {
  static instances: FakeSocket[] = []
  url: string
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  closed = false

  constructor(url: string) {
    this.url = url
    FakeSocket.instances.push(this)
  }
  close(): void {
    this.closed = true
  }
  emitOpen(): void {
    this.onopen?.()
  }
  emitMessage(data: unknown): void {
    this.onmessage?.({ data: JSON.stringify(data) })
  }
  emitClose(): void {
    this.onclose?.()
  }
}

function envelope(sequence: number, type = 'SNAPSHOT', payload: unknown = {}) {
  return {
    schemaVersion: '1.0',
    type,
    experimentId: 'exp-1',
    sequence,
    timestamp: new Date().toISOString(),
    payload,
  }
}

describe('ExperimentSocket 契约行为', () => {
  it('丢弃重复与乱序消息', () => {
    FakeSocket.instances = []
    const snapshots: number[] = []
    const socket = new ExperimentSocket(
      'exp-1',
      { onSnapshot: (p) => snapshots.push(p.step) },
      { socketFactory: (url) => new FakeSocket(url) as unknown as WebSocket },
    )
    socket.connect()
    const fake = FakeSocket.instances[0]
    fake.emitOpen()
    fake.emitMessage(envelope(3, 'SNAPSHOT', { step: 3, simulationTimeSeconds: 0, bodies: [] }))
    fake.emitMessage(envelope(2, 'SNAPSHOT', { step: 2, simulationTimeSeconds: 0, bodies: [] }))
    fake.emitMessage(envelope(3, 'SNAPSHOT', { step: 3, simulationTimeSeconds: 0, bodies: [] }))
    fake.emitMessage(envelope(4, 'SNAPSHOT', { step: 4, simulationTimeSeconds: 0, bodies: [] }))
    expect(snapshots).toEqual([3, 4])
    socket.close()
  })

  it('重连成功后触发 onResync', async () => {
    vi.useFakeTimers()
    try {
      FakeSocket.instances = []
      const resyncs: number[] = []
      const socket = new ExperimentSocket(
        'exp-1',
        { onResync: () => resyncs.push(FakeSocket.instances.length) },
        { socketFactory: (url) => new FakeSocket(url) as unknown as WebSocket, baseDelayMs: 5, maxDelayMs: 5 },
      )
      socket.connect()
      const first = FakeSocket.instances[0]
      first.emitOpen()
      first.emitClose()
      await vi.advanceTimersByTimeAsync(20)
      const second = FakeSocket.instances[1]
      expect(second).toBeDefined()
      second.emitOpen()
      expect(resyncs).toEqual([2])
      socket.close()
    } finally {
      vi.useRealTimers()
    }
  })

  it('setSequenceFloor 提高已处理序列号下限', () => {
    FakeSocket.instances = []
    const snapshots: number[] = []
    const socket = new ExperimentSocket(
      'exp-1',
      { onSnapshot: (p) => snapshots.push(p.step) },
      { socketFactory: (url) => new FakeSocket(url) as unknown as WebSocket },
    )
    socket.connect()
    const fake = FakeSocket.instances[0]
    fake.emitOpen()
    socket.setSequenceFloor(10)
    fake.emitMessage(envelope(8, 'SNAPSHOT', { step: 8, simulationTimeSeconds: 0, bodies: [] }))
    fake.emitMessage(envelope(11, 'SNAPSHOT', { step: 11, simulationTimeSeconds: 0, bodies: [] }))
    expect(snapshots).toEqual([11])
    socket.close()
  })

  it('协议违规消息触发 onProtocolViolation', () => {
    FakeSocket.instances = []
    const violations: string[] = []
    const socket = new ExperimentSocket(
      'exp-1',
      { onProtocolViolation: (reason) => violations.push(reason) },
      { socketFactory: (url) => new FakeSocket(url) as unknown as WebSocket },
    )
    socket.connect()
    const fake = FakeSocket.instances[0]
    fake.emitOpen()
    fake.emitMessage('not json')
    fake.emitMessage({ schemaVersion: '2.0' })
    expect(violations.length).toBe(2)
    socket.close()
  })

  it('snapshotToState 转换快照为 SimulationState', () => {
    const payload: SnapshotPayload = {
      step: 5,
      simulationTimeSeconds: 10,
      bodies: [
        { id: 'a', position: { x: 1, y: 2, z: 3 }, velocity: { x: 4, y: 5, z: 6 } },
      ],
    }
    const state = snapshotToState(payload)
    expect(state.step).toBe(5)
    expect(state.bodies[0].position).toEqual({ x: 1, y: 2, z: 3 })
  })
})

describe('ExperimentSocket 1.0/1.1 事件兼容（F4）', () => {
  it('1.1 NEAR_ENCOUNTER 的 { event } payload 原样交给 onNearEncounter', () => {
    FakeSocket.instances = []
    const received: unknown[] = []
    const socket = new ExperimentSocket(
      'exp-1',
      { onNearEncounter: (event) => received.push(event) },
      { socketFactory: (url) => new FakeSocket(url) as unknown as WebSocket },
    )
    socket.connect()
    const fake = FakeSocket.instances[0]
    fake.emitOpen()
    const event = {
      sequence: 7,
      eventId: 'aaaaaaaa-0000-4000-8000-000000000001',
      type: 'NEAR_ENCOUNTER',
      phase: 'UPDATE',
      step: 10,
      simulationTimeSeconds: 100,
      timestamp: new Date().toISOString(),
      message: '更新最近点',
      bodyIds: ['a', 'b'],
      closestDistanceMeters: 5e7,
      closestStep: 10,
      midpointPosition: { x: 1, y: 2, z: 3 },
    }
    fake.emitMessage(envelope(5, 'NEAR_ENCOUNTER', { event }))
    expect(received).toEqual([event])
    socket.close()
  })

  it('1.0 NEAR_ENCOUNTER 旧 payload 转换为缺少 eventId/中点的兼容事件', () => {
    FakeSocket.instances = []
    const received: unknown[] = []
    const socket = new ExperimentSocket(
      'exp-1',
      { onNearEncounter: (event) => received.push(event) },
      { socketFactory: (url) => new FakeSocket(url) as unknown as WebSocket },
    )
    socket.connect()
    const fake = FakeSocket.instances[0]
    fake.emitOpen()
    fake.emitMessage(
      envelope(5, 'NEAR_ENCOUNTER', {
        step: 9,
        simulationTimeSeconds: 90,
        bodyIds: ['a', 'b'],
        distanceMeters: 6e7,
        thresholdMeters: 5e8,
        message: '近遇',
      }),
    )
    const converted = received[0] as Record<string, unknown>
    expect(converted.eventId).toBeNull()
    expect(converted.phase).toBeUndefined()
    expect(converted.step).toBe(9)
    expect(converted.distanceMeters).toBe(6e7)
    expect(converted.thresholdMeters).toBe(5e8)
    expect(converted.closestDistanceMeters).toBeNull()
    expect(converted.midpointPosition).toBeUndefined()
    socket.close()
  })

  it('1.1 DIAGNOSTIC 的 { event } payload 交给 onDiagnostic', () => {
    FakeSocket.instances = []
    const received: unknown[] = []
    const socket = new ExperimentSocket(
      'exp-1',
      { onDiagnostic: (event) => received.push(event) },
      { socketFactory: (url) => new FakeSocket(url) as unknown as WebSocket },
    )
    socket.connect()
    const fake = FakeSocket.instances[0]
    fake.emitOpen()
    const event = {
      sequence: 8,
      eventId: 'bbbbbbbb-0000-4000-8000-000000000001',
      type: 'DIAGNOSTIC',
      phase: 'FINAL',
      step: 20,
      simulationTimeSeconds: 200,
      timestamp: new Date().toISOString(),
      message: '能量漂移',
      diagnostic: {
        code: 'ENERGY_DRIFT',
        severity: 'WARNING',
        causeCategory: 'NUMERICAL_ERROR',
        summary: '漂移',
        likelyCauses: [],
        evidence: {},
        recommendations: [],
      },
    }
    fake.emitMessage(envelope(6, 'DIAGNOSTIC', { event }))
    expect(received).toEqual([event])
    socket.close()
  })

  it('DIAGNOSTIC 缺少 event 字段触发协议违规但不影响后续消息', () => {
    FakeSocket.instances = []
    const violations: string[] = []
    const diagnostics: unknown[] = []
    const snapshots: number[] = []
    const socket = new ExperimentSocket(
      'exp-1',
      {
        onProtocolViolation: (reason) => violations.push(reason),
        onDiagnostic: (event) => diagnostics.push(event),
        onSnapshot: (payload) => snapshots.push(payload.step),
      },
      { socketFactory: (url) => new FakeSocket(url) as unknown as WebSocket },
    )
    socket.connect()
    const fake = FakeSocket.instances[0]
    fake.emitOpen()
    fake.emitMessage(envelope(7, 'DIAGNOSTIC', { step: 1 }))
    fake.emitMessage(envelope(8, 'SNAPSHOT', { step: 5, simulationTimeSeconds: 0, bodies: [] }))
    expect(violations.length).toBe(1)
    expect(diagnostics).toEqual([])
    expect(snapshots).toEqual([5])
    socket.close()
  })

  it('未知消息类型只记录一次开发日志并安全忽略', () => {
    FakeSocket.instances = []
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    const snapshots: number[] = []
    const socket = new ExperimentSocket(
      'exp-1',
      { onSnapshot: (payload) => snapshots.push(payload.step) },
      { socketFactory: (url) => new FakeSocket(url) as unknown as WebSocket },
    )
    socket.connect()
    const fake = FakeSocket.instances[0]
    fake.emitOpen()
    fake.emitMessage(envelope(9, 'FUTURE_TYPE', { any: true }))
    fake.emitMessage(envelope(9, 'FUTURE_TYPE', { any: true }))
    fake.emitMessage(envelope(10, 'SNAPSHOT', { step: 6, simulationTimeSeconds: 0, bodies: [] }))
    expect(warn).toHaveBeenCalledTimes(1)
    expect(snapshots).toEqual([6])
    warn.mockRestore()
    socket.close()
  })
})

describe('ExperimentSocket HEALTH', () => {
  it('dispatches HEALTH as an independent report', () => {
    FakeSocket.instances = []
    const received: unknown[] = []
    const socket = new ExperimentSocket(
      'exp-1',
      { onHealth: (health) => received.push(health) },
      { socketFactory: (url) => new FakeSocket(url) as unknown as WebSocket },
    )
    socket.connect()
    const fake = FakeSocket.instances[0]
    fake.emitOpen()
    fake.emitMessage(envelope(3, 'HEALTH', { status: 'WARNING' }))
    expect(received).toEqual([{ status: 'WARNING' }])
    socket.close()
  })
})
