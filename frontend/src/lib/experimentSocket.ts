/**
 * 实验 WebSocket 客户端。
 *
 * 契约要求：
 * - 消息按 sequence 单调递增，重复或过期消息必须丢弃。
 * - 断线使用带上限的指数退避重连。
 * - 重连成功后先由调用方通过 REST 获取全量状态，再接受序列号更大的增量消息，
 *   期间通过 onResync 回调通知上层暂停增量应用。
 * - 升级期同时接受 1.0 与 1.1：1.1 的 NEAR_ENCOUNTER/DIAGNOSTIC payload 为
 *   { event: SimulationEvent }，1.0 近遇在适配层转换为缺少 eventId/中点的兼容事件。
 * - 未识别的新消息类型记录一次开发日志并安全忽略，不能断开连接。
 */
import type { Metrics, SimulationEvent, SimulationState, Vector3 } from '../contracts'

export type WsEventType =
  | 'SNAPSHOT'
  | 'TRAJECTORY'
  | 'METRICS'
  | 'STATUS'
  | 'NEAR_ENCOUNTER'
  | 'DIAGNOSTIC'
  | 'ERROR'

export interface WsEnvelope<TPayload = unknown> {
  schemaVersion: '1.0' | '1.1'
  type: WsEventType
  experimentId: string
  sequence: number
  timestamp: string
  payload: TPayload
}

export interface WsBodyState {
  id: string
  position: Vector3
  velocity: Vector3
}

export interface SnapshotPayload {
  step: number
  simulationTimeSeconds: number
  bodies: WsBodyState[]
}

export interface TrajectoryPoint {
  step: number
  simulationTimeSeconds: number
  bodies: WsBodyState[]
}

export interface TrajectoryPayload {
  fromStep: number
  toStep: number
  stride: number
  points: TrajectoryPoint[]
}

export type MetricsPayload = Metrics & {
  step: number
  simulationTimeSeconds: number
}

export interface StatusPayload {
  status: 'QUEUED' | 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'CANCELLED' | 'FAILED'
  previousStatus?: StatusPayload['status'] | null
  step: number
  simulationTimeSeconds: number
  endReason?: 'MAX_STEPS' | 'TARGET_TIME' | 'CANCELLED' | 'ERROR' | null
  completionRatio?: number | null
  queuePosition?: number | null
  message?: string | null
}

/** 1.1 近遇/诊断统一 payload：携带完整逻辑事件。 */
export interface EventPayload {
  event: SimulationEvent
}

/** 1.0 近遇旧 payload：没有 eventId、phase 与真实最近点。 */
export interface NearEncounterV1Payload {
  step: number
  simulationTimeSeconds: number
  bodyIds: string[]
  distanceMeters: number
  thresholdMeters: number
  message?: string | null
}

export type NearEncounterPayload = EventPayload | NearEncounterV1Payload

export interface ErrorPayload {
  code: string
  message: string
  step?: number | null
  recoverable?: boolean | null
}

export type ConnectionState = 'IDLE' | 'CONNECTING' | 'OPEN' | 'RECONNECTING' | 'CLOSED'

export interface ExperimentSocketHandlers {
  onSnapshot?: (payload: SnapshotPayload, envelope: WsEnvelope<SnapshotPayload>) => void
  onTrajectory?: (payload: TrajectoryPayload, envelope: WsEnvelope<TrajectoryPayload>) => void
  onMetrics?: (payload: MetricsPayload, envelope: WsEnvelope<MetricsPayload>) => void
  onStatus?: (payload: StatusPayload, envelope: WsEnvelope<StatusPayload>) => void
  /** 近遇事件（1.0/1.1 均归一化为 SimulationEvent）。 */
  onNearEncounter?: (event: SimulationEvent, envelope: WsEnvelope) => void
  /** 1.1 诊断事件，payload 固定为 { event }。 */
  onDiagnostic?: (event: SimulationEvent, envelope: WsEnvelope) => void
  onError?: (payload: ErrorPayload, envelope: WsEnvelope<ErrorPayload>) => void
  onConnectionState?: (state: ConnectionState) => void
  /** 重连成功后触发，调用方必须重新拉取 REST 全量状态。 */
  onResync?: () => void
  /** 收到无法解析或不符合契约的消息时触发，便于测试断言。 */
  onProtocolViolation?: (reason: string, raw: unknown) => void
}

export interface ExperimentSocketOptions {
  /** 注入的 WebSocket 构造函数，测试用假实现替换。 */
  socketFactory?: (url: string) => WebSocket
  baseDelayMs?: number
  maxDelayMs?: number
  maxAttempts?: number
  /** 已知的最后序列号，通常来自 REST 的 lastSequence。 */
  initialSequence?: number
}

const DEFAULT_BASE_DELAY_MS = 500
const DEFAULT_MAX_DELAY_MS = 15000

function defaultWsBase(): string {
  const configured = import.meta.env.VITE_WS_BASE_URL
  if (configured && configured.trim() !== '') return configured.replace(/\/$/, '')
  if (typeof window === 'undefined') return 'ws://127.0.0.1/ws/v1'
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws/v1`
}

function isEnvelope(value: unknown): value is WsEnvelope {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Record<string, unknown>
  return (
    (candidate.schemaVersion === '1.0' || candidate.schemaVersion === '1.1') &&
    typeof candidate.type === 'string' &&
    typeof candidate.experimentId === 'string' &&
    typeof candidate.sequence === 'number' &&
    Number.isFinite(candidate.sequence) &&
    typeof candidate.timestamp === 'string' &&
    typeof candidate.payload === 'object' &&
    candidate.payload !== null
  )
}

function isEventPayload(payload: unknown): payload is EventPayload {
  if (typeof payload !== 'object' || payload === null) return false
  const candidate = payload as Record<string, unknown>
  return typeof candidate.event === 'object' && candidate.event !== null
}

/** 1.0 近遇载荷转换为缺少 eventId/中点的兼容事件。 */
export function nearEncounterV1ToEvent(payload: NearEncounterV1Payload, envelope: WsEnvelope): SimulationEvent {
  return {
    sequence: envelope.sequence,
    eventId: null,
    type: 'NEAR_ENCOUNTER',
    step: payload.step,
    simulationTimeSeconds: payload.simulationTimeSeconds,
    timestamp: envelope.timestamp,
    message: payload.message ?? '天体距离低于阈值。',
    bodyIds: payload.bodyIds,
    distanceMeters: payload.distanceMeters,
    thresholdMeters: payload.thresholdMeters,
    triggerDistanceMeters: null,
    closestDistanceMeters: null,
    closestStep: null,
    closestSimulationTimeSeconds: null,
  }
}

/** 把任意版本近遇载荷归一化为 SimulationEvent。 */
export function normalizeNearEncounter(
  payload: NearEncounterPayload,
  envelope: WsEnvelope,
): SimulationEvent {
  if (isEventPayload(payload)) return payload.event
  return nearEncounterV1ToEvent(payload as NearEncounterV1Payload, envelope)
}

/** 已记录过开发日志的未知类型，避免刷屏。 */
const loggedUnknownTypes = new Set<string>()

export class ExperimentSocket {
  private socket: WebSocket | null = null
  private attempts = 0
  private timer: ReturnType<typeof setTimeout> | null = null
  private closedByUser = false
  private lastSequence: number
  private hasConnectedBefore = false
  private state: ConnectionState = 'IDLE'

  constructor(
    private readonly experimentId: string,
    private readonly handlers: ExperimentSocketHandlers = {},
    private readonly options: ExperimentSocketOptions = {},
  ) {
    this.lastSequence = options.initialSequence ?? 0
  }

  get connectionState(): ConnectionState {
    return this.state
  }

  get processedSequence(): number {
    return this.lastSequence
  }

  /** REST 全量同步后调用，避免把已包含在全量状态中的增量重复应用。 */
  setSequenceFloor(sequence: number): void {
    if (Number.isFinite(sequence) && sequence > this.lastSequence) {
      this.lastSequence = sequence
    }
  }

  connect(): void {
    this.closedByUser = false
    this.open()
  }

  close(): void {
    this.closedByUser = true
    this.clearTimer()
    if (this.socket) {
      this.socket.onopen = null
      this.socket.onmessage = null
      this.socket.onclose = null
      this.socket.onerror = null
      try {
        this.socket.close()
      } catch {
        // 关闭失败不影响后续清理。
      }
      this.socket = null
    }
    this.setState('CLOSED')
  }

  private open(): void {
    const url = `${defaultWsBase()}/experiments/${this.experimentId}`
    this.setState(this.hasConnectedBefore ? 'RECONNECTING' : 'CONNECTING')
    const factory = this.options.socketFactory ?? ((target: string) => new WebSocket(target))
    let socket: WebSocket
    try {
      socket = factory(url)
    } catch {
      this.scheduleReconnect()
      return
    }
    this.socket = socket

    socket.onopen = () => {
      this.attempts = 0
      this.setState('OPEN')
      if (this.hasConnectedBefore) {
        // 重连成功：调用方需要重新拉取全量状态。
        this.handlers.onResync?.()
      }
      this.hasConnectedBefore = true
    }

    socket.onmessage = (event: MessageEvent) => {
      this.handleMessage(event.data)
    }

    socket.onerror = () => {
      // onclose 会紧随其后触发，这里不重复安排重连。
    }

    socket.onclose = () => {
      this.socket = null
      if (this.closedByUser) {
        this.setState('CLOSED')
        return
      }
      this.scheduleReconnect()
    }
  }

  private handleMessage(raw: unknown): void {
    if (typeof raw !== 'string') {
      this.handlers.onProtocolViolation?.('消息不是文本帧', raw)
      return
    }
    let parsed: unknown
    try {
      parsed = JSON.parse(raw)
    } catch {
      this.handlers.onProtocolViolation?.('消息不是合法 JSON', raw)
      return
    }
    if (!isEnvelope(parsed)) {
      this.handlers.onProtocolViolation?.('消息不符合信封契约', parsed)
      return
    }
    if (parsed.experimentId !== this.experimentId) {
      this.handlers.onProtocolViolation?.('消息属于其他实验', parsed)
      return
    }
    // 乱序与重复消息按契约丢弃。
    if (parsed.sequence <= this.lastSequence) {
      return
    }
    this.lastSequence = parsed.sequence
    this.dispatch(parsed)
  }

  private dispatch(envelope: WsEnvelope): void {
    switch (envelope.type) {
      case 'SNAPSHOT':
        this.handlers.onSnapshot?.(envelope.payload as SnapshotPayload, envelope as WsEnvelope<SnapshotPayload>)
        return
      case 'TRAJECTORY':
        this.handlers.onTrajectory?.(envelope.payload as TrajectoryPayload, envelope as WsEnvelope<TrajectoryPayload>)
        return
      case 'METRICS':
        this.handlers.onMetrics?.(envelope.payload as MetricsPayload, envelope as WsEnvelope<MetricsPayload>)
        return
      case 'STATUS':
        this.handlers.onStatus?.(envelope.payload as StatusPayload, envelope as WsEnvelope<StatusPayload>)
        return
      case 'NEAR_ENCOUNTER':
        this.handlers.onNearEncounter?.(
          normalizeNearEncounter(envelope.payload as NearEncounterPayload, envelope),
          envelope,
        )
        return
      case 'DIAGNOSTIC':
        if (isEventPayload(envelope.payload)) {
          this.handlers.onDiagnostic?.(envelope.payload.event, envelope)
        } else {
          // 诊断没有 1.0 旧格式，不符合契约时安全忽略。
          this.handlers.onProtocolViolation?.('DIAGNOSTIC 载荷缺少 event 字段', envelope)
        }
        return
      case 'ERROR':
        this.handlers.onError?.(envelope.payload as ErrorPayload, envelope as WsEnvelope<ErrorPayload>)
        return
      default: {
        // 升级期可能出现的新消息类型：记录一次开发日志并安全忽略，不断开连接。
        if (!loggedUnknownTypes.has(envelope.type)) {
          loggedUnknownTypes.add(envelope.type)
          console.warn(`[experimentSocket] 忽略未知 WS 消息类型: ${envelope.type}`)
        }
      }
    }
  }

  private scheduleReconnect(): void {
    const maxAttempts = this.options.maxAttempts ?? Number.POSITIVE_INFINITY
    if (this.attempts >= maxAttempts) {
      this.setState('CLOSED')
      return
    }
    const base = this.options.baseDelayMs ?? DEFAULT_BASE_DELAY_MS
    const max = this.options.maxDelayMs ?? DEFAULT_MAX_DELAY_MS
    const delay = Math.min(max, base * 2 ** this.attempts)
    this.attempts += 1
    this.setState('RECONNECTING')
    this.clearTimer()
    this.timer = setTimeout(() => {
      this.timer = null
      if (!this.closedByUser) this.open()
    }, delay)
  }

  private clearTimer(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer)
      this.timer = null
    }
  }

  private setState(state: ConnectionState): void {
    if (this.state === state) return
    this.state = state
    this.handlers.onConnectionState?.(state)
  }
}

/** 把 WebSocket 快照载荷转换为契约里的 SimulationState 形状，便于统一存储。 */
export function snapshotToState(payload: SnapshotPayload): SimulationState {
  return {
    step: payload.step,
    simulationTimeSeconds: payload.simulationTimeSeconds,
    bodies: payload.bodies.map((body) => ({
      id: body.id,
      position: body.position,
      velocity: body.velocity,
    })),
  }
}
