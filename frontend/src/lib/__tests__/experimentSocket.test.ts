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
