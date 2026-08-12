/**
 * 实验队列与实时联调。
 *
 * 关键约束：
 * - REST 是权威全量来源，WebSocket 只提供增量。
 * - 重连期间暂停增量应用，等 REST 全量同步完成再恢复。
 * - 轨迹按天体保留最近 LIVE_TRAIL_LIMIT 个三维点，切换投影时复用同一份数据。
 * - 历史回看使用独立 PlaybackBuffer；REVIEW 不被实时帧覆盖，LIVE 始终摄入最新权威状态。
 * - 拖动只查缓存，pointerup/事件定位才请求精确步；历史请求使用 AbortController 与 100ms 防抖。
 */
import { defineStore } from 'pinia'
import { computed, markRaw, ref, shallowRef } from 'vue'
import {
  LIVE_TRAIL_LIMIT,
  isActionAllowed,
  type Experiment,
  type ExperimentAction,
  type ExperimentStatus,
  type ExperimentSummary,
  type Metrics,
  type ReplayJob,
  type SimulationConfig,
  type SimulationEvent,
  type SimulationState,
} from '../contracts'
import { ApiError, api } from '../lib/apiClient'
import {
  ExperimentSocket,
  snapshotToState,
  type ConnectionState,
  type MetricsPayload,
  type StatusPayload,
  type TrajectoryPayload,
} from '../lib/experimentSocket'
import { PlaybackBuffer, type InterpolatedPoint } from '../lib/playbackBuffer'
import { SnapshotBuffer } from '../lib/snapshotBuffer'
import { TrajectoryBuffer } from '../lib/trajectoryBuffer'

/** 图表使用的时间序列点，单位与契约一致（SI）。 */
export interface MetricSample {
  step: number
  simulationTimeSeconds: number
  totalEnergyJoules: number
  relativeEnergyDrift: number
  angularMomentumMagnitude: number
  minimumPairDistanceMeters: number
}

export type PlaybackMode = 'LIVE' | 'REVIEW_PAUSED' | 'REVIEW_PLAYING'
export type PlaybackPrecision = 'EXACT' | 'APPROXIMATE' | 'RESOLVING' | 'ERROR'

export interface EncounterAlert {
  /** 稳定键：eventId，1.0 回退为 sequence 前缀。 */
  key: string
  event: SimulationEvent
}

const MAX_METRIC_SAMPLES = 3000
const MAX_EVENTS = 1000
const MAX_ALERTS = 20
const REPLAY_POLL_MS = 200
const HISTORY_DEBOUNCE_MS = 100
/** 历史播放每秒推进当前可用范围的 1%。 */
const REVIEW_TICK_MS = 250
const REVIEW_RATE_PER_SECOND = 0.01

/** 逻辑事件键：新事件用 eventId，旧 1.0 事件回退 sequence。 */
export function eventKey(event: Pick<SimulationEvent, 'eventId' | 'sequence'>): string {
  return event.eventId ?? `seq-${event.sequence}`
}

export const useExperimentsStore = defineStore('experiments', () => {
  const summaries = ref<ExperimentSummary[]>([])
  const listLoading = ref(false)
  const listError = ref<string | null>(null)
  const backendReachable = ref<boolean | null>(null)

  const current = ref<Experiment | null>(null)
  const currentLoading = ref(false)
  const currentError = ref<string | null>(null)

  const liveState = ref<SimulationState | null>(null)
  const liveMetrics = ref<Metrics | null>(null)
  const metricSamples = ref<MetricSample[]>([])
  const events = ref<SimulationEvent[]>([])
  /** 近距离事件的非阻塞提示队列，按稳定键消费后移除。 */
  const encounterAlerts = ref<EncounterAlert[]>([])

  const connectionState = ref<ConnectionState>('IDLE')
  const actionPending = ref<ExperimentAction | null>(null)
  const actionError = ref<string | null>(null)

  /** Display interpolation is kept separate from authoritative liveState. */
  const snapshotBuffer = markRaw(new SnapshotBuffer())

  /** Ring-backed trail data; only TRAJECTORY messages append to this buffer. */
  const trails = shallowRef<TrajectoryBuffer>(markRaw(new TrajectoryBuffer(LIVE_TRAIL_LIMIT)))
  const trailVersion = ref(0)

  // ---- 历史回看状态（F5） ----
  const playbackBufferRef = shallowRef<PlaybackBuffer | null>(null)
  const playbackMode = ref<PlaybackMode>('LIVE')
  const playbackPrecision = ref<PlaybackPrecision>('EXACT')
  const cursorStep = ref(0)
  const cursorTimeSeconds = ref(0)
  const selectedEventId = ref<string | null>(null)
  const historyLoading = ref(false)
  const historyError = ref<string | null>(null)
  const activeReplayJob = ref<ReplayJob | null>(null)
  const availableFromStep = ref<number | null>(null)
  const availableToStep = ref<number | null>(null)
  const archiveSampleStride = ref(1)

  let socket: ExperimentSocket | null = null
  /** 重连后 REST 全量同步未完成时挂起增量。 */
  let resyncing = false
  let historyAbort: AbortController | null = null
  let historyDebounceTimer: ReturnType<typeof setTimeout> | null = null
  let replayPollTimer: ReturnType<typeof setTimeout> | null = null
  let reviewTickTimer: ReturnType<typeof setInterval> | null = null
  /** 当前持有的回放任务；离开回看、切换实验或卸载时 DELETE。 */
  let ownedReplayJobId: string | null = null

  const queued = computed(() => summaries.value.filter((item) => item.status === 'QUEUED'))
  const running = computed(() => summaries.value.find((item) => item.status === 'RUNNING') ?? null)
  const totalStorageBytes = computed(() =>
    summaries.value.reduce((sum, item) => sum + (item.storageBytes ?? 0), 0),
  )

  const currentStatus = computed<ExperimentStatus | null>(() => current.value?.status ?? null)

  /** 计划结束步：两个结束条件同时存在时先达到者结束。 */
  const plannedEndStep = computed(() => {
    const experiment = current.value
    if (!experiment) return 0
    const { maxSteps, targetSimulationTimeSeconds, timeStepSeconds } = experiment.config
    const candidates: number[] = []
    if (maxSteps !== null && maxSteps !== undefined && maxSteps > 0) candidates.push(maxSteps)
    if (
      targetSimulationTimeSeconds !== null &&
      targetSimulationTimeSeconds !== undefined &&
      timeStepSeconds > 0
    ) {
      candidates.push(Math.ceil(targetSimulationTimeSeconds / timeStepSeconds))
    }
    if (candidates.length === 0) return Math.max(availableToStep.value ?? 0, experiment.progress.step)
    return Math.min(...candidates)
  })

  const isReviewing = computed(() => playbackMode.value !== 'LIVE')

  /** 视图统一选择：LIVE 用实时状态，REVIEW 用回放缓存（近似插值只用于显示）。 */
  const displayState = computed<SimulationState | null>(() => {
    if (!isReviewing.value) return liveState.value
    const point = playbackBufferRef.value?.interpolateAt(cursorStep.value)
    return point ? interpolatedToState(point) : liveState.value
  })

  /** 历史轨迹只绘制到 cursorStep；LIVE 使用当前权威步。 */
  const trailCutoffStep = computed(() =>
    isReviewing.value ? cursorStep.value : (liveState.value?.step ?? current.value?.progress.step ?? Number.POSITIVE_INFINITY),
  )

  function can(action: ExperimentAction): boolean {
    const status = currentStatus.value
    if (!status) return false
    return isActionAllowed(status, action) && actionPending.value === null
  }

  function resetLiveBuffers(): void {
    liveState.value = null
    liveMetrics.value = null
    metricSamples.value = []
    encounterAlerts.value = []
    trails.value = markRaw(new TrajectoryBuffer(LIVE_TRAIL_LIMIT))
    snapshotBuffer.reset()
    trailVersion.value += 1
  }

  function clearPlaybackState(): void {
    stopReplayPolling()
    stopReviewTicker()
    clearHistoryDebounce()
    historyAbort?.abort()
    historyAbort = null
    playbackBufferRef.value = null
    playbackMode.value = 'LIVE'
    playbackPrecision.value = 'EXACT'
    cursorStep.value = 0
    cursorTimeSeconds.value = 0
    selectedEventId.value = null
    historyLoading.value = false
    historyError.value = null
    activeReplayJob.value = null
    ownedReplayJobId = null
    availableFromStep.value = null
    availableToStep.value = null
    archiveSampleStride.value = 1
  }

  async function loadList(): Promise<void> {
    listLoading.value = true
    listError.value = null
    try {
      summaries.value = await api.listExperiments()
      backendReachable.value = true
    } catch (error) {
      backendReachable.value = false
      listError.value = error instanceof ApiError ? error.message : '加载实验列表失败。'
    } finally {
      listLoading.value = false
    }
  }

  /** 按 eventId upsert 事件；1.0 旧事件回退 sequence 只读键。 */
  function upsertEvent(event: SimulationEvent): void {
    const key = eventKey(event)
    const index = events.value.findIndex((item) => eventKey(item) === key)
    if (index >= 0) {
      events.value = events.value.map((item, i) => (i === index ? event : item))
    } else {
      events.value = [...events.value, event].slice(-MAX_EVENTS)
    }
  }

  function pushEncounterAlert(event: SimulationEvent): void {
    const key = eventKey(event)
    const existing = encounterAlerts.value.findIndex((item) => item.key === key)
    if (existing >= 0) {
      encounterAlerts.value = encounterAlerts.value.map((item, i) => (i === existing ? { key, event } : item))
    } else {
      encounterAlerts.value = [...encounterAlerts.value, { key, event }].slice(-MAX_ALERTS)
    }
  }

  /** 全量同步：REST 结果直接覆盖本地状态，并把序列号下限交给 WebSocket。 */
  async function loadExperiment(id: string, options: { keepBuffers?: boolean } = {}): Promise<void> {
    currentLoading.value = true
    currentError.value = null
    try {
      const experiment = await api.getExperiment(id)
      current.value = experiment
      if (!options.keepBuffers) {
        resetLiveBuffers()
        clearPlaybackState()
        playbackBufferRef.value = markRaw(new PlaybackBuffer(experiment.config.bodies.map((b) => b.id ?? b.name)))
      }
      if (experiment.state) {
        liveState.value = experiment.state
        snapshotBuffer.push(experiment.state)
      }
      if (experiment.metrics) {
        liveMetrics.value = experiment.metrics
        appendMetricSample(experiment.metrics, experiment.progress.step, experiment.progress.simulationTimeSeconds)
      }
      events.value = experiment.events ?? []
      if (typeof experiment.lastSequence === 'number') {
        socket?.setSequenceFloor(experiment.lastSequence)
      }
      // 初次选择实验时请求整个可用范围的 overview。
      if (!options.keepBuffers) {
        void loadHistoryOverview(id)
      }
    } catch (error) {
      currentError.value = error instanceof ApiError ? error.message : '加载实验详情失败。'
      throw error
    } finally {
      currentLoading.value = false
      resyncing = false
    }
  }

  function appendMetricSample(metrics: Metrics, step: number, simulationTimeSeconds: number): void {
    const sample: MetricSample = {
      step,
      simulationTimeSeconds,
      totalEnergyJoules: metrics.totalEnergyJoules,
      relativeEnergyDrift: metrics.relativeEnergyDrift,
      angularMomentumMagnitude: metrics.angularMomentumMagnitude,
      minimumPairDistanceMeters: metrics.minimumPairDistanceMeters,
    }
    const last = metricSamples.value[metricSamples.value.length - 1]
    if (last && last.step === sample.step) return
    metricSamples.value = [...metricSamples.value, sample].slice(-MAX_METRIC_SAMPLES)
  }

  function pushTrailPoint(bodyId: string, step: number, x: number, y: number, z: number): boolean {
    return trails.value.getOrCreate(bodyId).append(step, x, y, z)
  }

  // ---- 历史查询与精确重建（F5） ----

  function clearHistoryDebounce(): void {
    if (historyDebounceTimer !== null) {
      clearTimeout(historyDebounceTimer)
      historyDebounceTimer = null
    }
  }

  function applyHistoryResponse(response: Awaited<ReturnType<typeof api.getHistory>>): void {
    const buffer = playbackBufferRef.value
    if (buffer && response.points.length > 0) {
      buffer.replaceOverview(response.points)
    }
    if (response.currentState) {
      // currentState 按 step 去重合并进 live 层，保证运行中上界可见。
      playbackBufferRef.value?.appendLive([response.currentState])
    }
    if (response.availableFromStep !== null && response.availableFromStep !== undefined) {
      availableFromStep.value = response.availableFromStep
    }
    if (response.availableToStep !== null && response.availableToStep !== undefined) {
      availableToStep.value = response.availableToStep
    }
    archiveSampleStride.value = response.archiveSampleStride
  }

  /** 初次 overview：请求整个可用范围（最多 2,000 点）。 */
  async function loadHistoryOverview(id: string): Promise<void> {
    const experiment = current.value
    if (!experiment) return
    historyAbort?.abort()
    const controller = new AbortController()
    historyAbort = controller
    historyLoading.value = true
    historyError.value = null
    try {
      const response = await api.getHistory(
        id,
        { fromStep: 0, toStep: experiment.progress.step, maxPoints: 2000 },
        controller.signal,
      )
      if (controller.signal.aborted) return
      applyHistoryResponse(response)
    } catch (error) {
      if (controller.signal.aborted) return
      if (error instanceof ApiError && !error.isNotFound) {
        historyError.value = error.message
      }
    } finally {
      if (historyAbort === controller) {
        historyLoading.value = false
        historyAbort = null
      }
    }
  }

  /** focus 范围：以目标步为中心，防抖 100ms，新的请求取消旧请求。 */
  function scheduleFocusRange(id: string, centerStep: number): void {
    clearHistoryDebounce()
    historyDebounceTimer = setTimeout(() => {
      historyDebounceTimer = null
      void loadFocusRange(id, centerStep)
    }, HISTORY_DEBOUNCE_MS)
  }

  async function loadFocusRange(id: string, centerStep: number): Promise<void> {
    historyAbort?.abort()
    const controller = new AbortController()
    historyAbort = controller
    historyLoading.value = true
    try {
      const radius = 1000
      const response = await api.getHistory(
        id,
        {
          fromStep: Math.max(0, centerStep - radius),
          toStep: centerStep + radius,
          maxPoints: 2000,
        },
        controller.signal,
      )
      if (controller.signal.aborted) return
      const buffer = playbackBufferRef.value
      if (buffer && response.points.length > 0) buffer.replaceFocus(response.points)
      if (response.currentState) buffer?.appendLive([response.currentState])
      if (response.availableToStep !== null && response.availableToStep !== undefined) {
        availableToStep.value = response.availableToStep
      }
    } catch (error) {
      if (controller.signal.aborted) return
      if (error instanceof ApiError && !error.isNotFound) {
        historyError.value = error.message
      }
    } finally {
      if (historyAbort === controller) {
        historyLoading.value = false
        historyAbort = null
      }
    }
  }

  function stopReplayPolling(): void {
    if (replayPollTimer !== null) {
      clearTimeout(replayPollTimer)
      replayPollTimer = null
    }
  }

  function scheduleReplayPoll(id: string): void {
    stopReplayPolling()
    replayPollTimer = setTimeout(() => {
      replayPollTimer = null
      void pollReplayJob(id)
    }, REPLAY_POLL_MS)
  }

  async function pollReplayJob(id: string): Promise<void> {
    const job = activeReplayJob.value
    if (!job) return
    try {
      const updated = await api.getReplayJob(id, job.jobId)
      activeReplayJob.value = updated
      if (updated.status === 'COMPLETED' && updated.result) {
        playbackBufferRef.value?.setExact(updated.result)
        playbackPrecision.value = 'EXACT'
        cursorStep.value = updated.result.step
        cursorTimeSeconds.value = updated.result.simulationTimeSeconds
        stopReplayPolling()
      } else if (updated.status === 'CANCELLED' || updated.status === 'FAILED') {
        playbackPrecision.value = 'ERROR'
        historyError.value = updated.error ?? '精确重建任务失败。'
        stopReplayPolling()
      } else {
        scheduleReplayPoll(id)
      }
    } catch (error) {
      if (error instanceof ApiError && error.isNotFound) {
        playbackPrecision.value = 'ERROR'
        historyError.value = '回放任务已过期或不存在。'
        activeReplayJob.value = null
        ownedReplayJobId = null
        stopReplayPolling()
      } else {
        // 网络抖动：继续轮询。
        scheduleReplayPoll(id)
      }
    }
  }

  async function deleteOwnedReplayJob(id: string): Promise<void> {
    const jobId = ownedReplayJobId
    if (!jobId) return
    ownedReplayJobId = null
    try {
      await api.deleteReplayJob(id, jobId)
    } catch {
      // 任务可能已过期（404）或已被清理，忽略。
    }
  }

  /** 创建精确重建任务并轮询；新任务取消并 DELETE 旧任务。 */
  async function requestExactStep(id: string, targetStep: number): Promise<void> {
    stopReplayPolling()
    await deleteOwnedReplayJob(id)
    playbackPrecision.value = 'RESOLVING'
    historyError.value = null
    try {
      const job = await api.createReplayJob(id, targetStep)
      activeReplayJob.value = job
      ownedReplayJobId = job.jobId
      if (job.status === 'COMPLETED' && job.result) {
        playbackBufferRef.value?.setExact(job.result)
        playbackPrecision.value = 'EXACT'
        cursorStep.value = job.result.step
        cursorTimeSeconds.value = job.result.simulationTimeSeconds
      } else {
        scheduleReplayPoll(id)
      }
    } catch (error) {
      if (error instanceof ApiError && error.code === 'REPLAY_QUEUE_FULL') {
        playbackPrecision.value = 'ERROR'
        historyError.value = '精确重建队列已满，请稍后重试。'
      } else if (error instanceof ApiError) {
        playbackPrecision.value = 'ERROR'
        historyError.value = error.message
      } else {
        playbackPrecision.value = 'ERROR'
        historyError.value = '精确重建请求失败。'
      }
    }
  }

  /** 事件定位：优先 closestStep，缺失时使用 step。 */
  async function locateEvent(id: string, event: SimulationEvent): Promise<void> {
    const target = event.closestStep ?? event.step
    selectedEventId.value = event.eventId ?? `seq-${event.sequence}`
    await enterReview(id, target)
  }

  /** 进入回看：冻结游标、请求精确步并填充 focus 缓存。 */
  async function enterReview(id: string, targetStep: number): Promise<void> {
    const experiment = current.value
    if (!experiment) return
    playbackMode.value = 'REVIEW_PAUSED'
    stopReviewTicker()
    cursorStep.value = Math.max(0, Math.floor(targetStep))
    cursorTimeSeconds.value = cursorStep.value * experiment.config.timeStepSeconds
    scheduleFocusRange(id, cursorStep.value)
    await requestExactStep(id, cursorStep.value)
  }

  /** 游标进入最新权威步一个积分步以内时自动返回 LIVE。 */
  function maybeAutoReturnToLive(): boolean {
    const latest = liveState.value?.step ?? current.value?.progress.step ?? Number.POSITIVE_INFINITY
    if (playbackMode.value !== 'LIVE' && cursorStep.value >= latest - 1) {
      const id = current.value?.id
      stopReplayPolling()
      stopReviewTicker()
      if (id) void deleteOwnedReplayJob(id)
      playbackMode.value = 'LIVE'
      playbackPrecision.value = 'EXACT'
      selectedEventId.value = null
      activeReplayJob.value = null
      return true
    }
    return false
  }

  /** pointerdown 进入回看并冻结游标；只查缓存，不发请求。 */
  function beginReviewScrub(step: number): void {
    if (playbackMode.value === 'LIVE') {
      playbackMode.value = 'REVIEW_PAUSED'
    }
    stopReviewTicker()
    seekCursorFromCache(step)
  }

  /** pointermove 合帧后只查缓存更新游标，不发请求、不触发精确重建。 */
  function seekCursorFromCache(step: number): void {
    if (playbackMode.value === 'LIVE') return
    const bounded = Math.max(0, Math.floor(step))
    cursorStep.value = bounded
    if (maybeAutoReturnToLive()) return
    const point = playbackBufferRef.value?.interpolateAt(bounded)
    cursorTimeSeconds.value = point?.simulationTimeSeconds ?? cursorTimeSeconds.value
    playbackPrecision.value = point?.approximate ? 'APPROXIMATE' : playbackPrecision.value
  }

  /** 单击跳转：总范围 1%，至少一步。 */
  function jumpByPercent(direction: -1 | 1): void {
    const upper = availableToStep.value ?? cursorStep.value
    const lower = availableFromStep.value ?? 0
    const range = Math.max(1, plannedEndStep.value)
    const delta = Math.max(1, Math.ceil(range * 0.01))
    const target = cursorStep.value + direction * delta
    cursorStep.value = Math.min(upper, Math.max(lower, target))
    cursorTimeSeconds.value =
      (playbackBufferRef.value?.interpolateAt(cursorStep.value)?.simulationTimeSeconds) ??
      cursorTimeSeconds.value
  }

  function startReviewPlayback(): void {
    if (playbackMode.value === 'LIVE') return
    playbackMode.value = 'REVIEW_PLAYING'
    stopReviewTicker()
    reviewTickTimer = setInterval(() => {
      const upper = availableToStep.value ?? cursorStep.value
      const lower = availableFromStep.value ?? 0
      const range = Math.max(1, upper - lower)
      const perSecond = Math.max(1, Math.ceil(range * REVIEW_RATE_PER_SECOND))
      const delta = Math.max(1, Math.round((perSecond * REVIEW_TICK_MS) / 1000))
      const target = Math.min(upper, cursorStep.value + delta)
      cursorStep.value = target
      cursorTimeSeconds.value =
        playbackBufferRef.value?.interpolateAt(target)?.simulationTimeSeconds ?? cursorTimeSeconds.value
      if (target >= upper) {
        playbackMode.value = 'REVIEW_PAUSED'
        stopReviewTicker()
      }
    }, REVIEW_TICK_MS)
  }

  function pauseReviewPlayback(): void {
    if (playbackMode.value === 'REVIEW_PLAYING') playbackMode.value = 'REVIEW_PAUSED'
    stopReviewTicker()
  }

  function stopReviewTicker(): void {
    if (reviewTickTimer !== null) {
      clearInterval(reviewTickTimer)
      reviewTickTimer = null
    }
  }

  /** 返回实时：取消回放任务、清除选中历史态，恢复 SnapshotBuffer 展示。 */
  function returnToLive(): void {
    const id = current.value?.id
    stopReplayPolling()
    stopReviewTicker()
    if (id) void deleteOwnedReplayJob(id)
    playbackMode.value = 'LIVE'
    playbackPrecision.value = 'EXACT'
    selectedEventId.value = null
    historyError.value = null
    activeReplayJob.value = null
  }

  /** 切换实验、重启或删除前统一取消任务并清空历史状态。 */
  function cancelReplayAndCleanup(): void {
    const id = current.value?.id
    stopReplayPolling()
    stopReviewTicker()
    clearHistoryDebounce()
    historyAbort?.abort()
    historyAbort = null
    if (id) void deleteOwnedReplayJob(id)
    activeReplayJob.value = null
  }

  function connect(id: string): void {
    disconnect()
    socket = new ExperimentSocket(
      id,
      {
        onConnectionState: (state) => {
          connectionState.value = state
          // 连接建立后刷新列表，确保队列状态与实时状态一致。
          if (state === 'OPEN') {
            void loadList()
          }
        },
        onResync: () => {
          // 重连成功：先挂起增量，等 REST 全量同步完成。
          resyncing = true
          void loadExperiment(id, { keepBuffers: true }).catch(() => {
            // 错误已记录在 currentError 中。
          })
        },
        onSnapshot: (payload) => {
          if (resyncing) return
          const state = snapshotToState(payload)
          liveState.value = state
          snapshotBuffer.push(state)
        },
        onTrajectory: (payload: TrajectoryPayload) => {
          if (resyncing) return
          let changed = false
          for (const point of payload.points) {
            // 回放 live 层也摄入实时轨迹，供回看拖动显示。
            playbackBufferRef.value?.appendLive([snapshotToState(point)])
            for (const body of point.bodies) {
              changed = pushTrailPoint(
                body.id,
                point.step,
                body.position.x,
                body.position.y,
                body.position.z,
              ) || changed
            }
          }
          if (changed) trailVersion.value += 1
        },
        onMetrics: (payload: MetricsPayload) => {
          if (resyncing) return
          liveMetrics.value = payload
          appendMetricSample(payload, payload.step, payload.simulationTimeSeconds)
        },
        onStatus: (payload: StatusPayload) => {
          if (resyncing) return
          if (current.value) {
            current.value = {
              ...current.value,
              status: payload.status,
              endReason: payload.endReason ?? current.value.endReason,
              progress: {
                ...current.value.progress,
                step: payload.step,
                simulationTimeSeconds: payload.simulationTimeSeconds,
                completionRatio: payload.completionRatio ?? current.value.progress.completionRatio,
              },
            }
          }
          // 状态变化会影响队列顺序与按钮可用性，刷新列表。
          void loadList()
        },
        onNearEncounter: (event: SimulationEvent) => {
          if (resyncing) return
          upsertEvent(event)
          pushEncounterAlert(event)
        },
        onDiagnostic: (event: SimulationEvent) => {
          if (resyncing) return
          upsertEvent(event)
        },
        onError: (payload) => {
          actionError.value = payload.message
        },
      },
      { initialSequence: current.value?.lastSequence ?? 0 },
    )
    socket.connect()
  }

  function disconnect(): void {
    socket?.close()
    socket = null
    connectionState.value = 'IDLE'
    resyncing = false
  }

  function dismissEncounter(key: string): void {
    encounterAlerts.value = encounterAlerts.value.filter((item) => item.key !== key)
  }

  async function createExperiment(config: SimulationConfig, name?: string): Promise<Experiment | null> {
    actionError.value = null
    try {
      const created = await api.createExperiment({ name, config })
      await loadList()
      return created
    } catch (error) {
      actionError.value = error instanceof ApiError ? error.message : '创建实验失败。'
      return null
    }
  }

  async function submitActionFor(
    experimentId: string,
    action: ExperimentAction,
    config?: SimulationConfig,
  ): Promise<boolean> {
    actionPending.value = action
    actionError.value = null
    try {
      const updated = await api.submitAction(experimentId, {
        action,
        // 契约要求：只有 RESTART 可携带配置，其他动作必须为 null。
        config: action === 'RESTART' ? (config ?? null) : null,
      })
      if (current.value?.id === experimentId) {
        current.value = updated
      }
      if (action === 'RESTART' && current.value?.id === experimentId) {
        resetLiveBuffers()
        cancelReplayAndCleanup()
        clearPlaybackState()
        playbackBufferRef.value = markRaw(
          new PlaybackBuffer(updated.config.bodies.map((b) => b.id ?? b.name)),
        )
        void loadHistoryOverview(experimentId)
      }
      await loadList()
      return true
    } catch (error) {
      if (error instanceof ApiError) {
        actionError.value = error.message
        // 状态冲突说明本地视图已过期，立即全量刷新。
        if (error.isConflict) {
          if (current.value?.id === experimentId) {
            await loadExperiment(experimentId, { keepBuffers: true }).catch(() => undefined)
          }
          await loadList()
        }
      } else {
        actionError.value = '操作失败。'
      }
      return false
    } finally {
      actionPending.value = null
    }
  }

  async function submitAction(action: ExperimentAction, config?: SimulationConfig): Promise<boolean> {
    const experiment = current.value
    if (!experiment) return false
    return submitActionFor(experiment.id, action, config)
  }

  async function updateQueuedConfig(id: string, config: SimulationConfig, name?: string): Promise<boolean> {
    actionError.value = null
    try {
      const updated = await api.updateExperiment(id, { name, config })
      if (current.value?.id === id) current.value = updated
      await loadList()
      return true
    } catch (error) {
      actionError.value = error instanceof ApiError ? error.message : '更新实验失败。'
      return false
    }
  }

  async function reorderQueue(orderedIds: string[]): Promise<boolean> {
    const previous = summaries.value
    actionError.value = null
    try {
      summaries.value = await api.reorderQueue(orderedIds)
      return true
    } catch (error) {
      // 失败时回滚本地顺序，避免界面与服务端不一致。
      summaries.value = previous
      actionError.value = error instanceof ApiError ? error.message : '调整队列顺序失败。'
      if (error instanceof ApiError && error.isConflict) {
        await loadList()
      }
      return false
    }
  }

  async function deleteExperiment(id: string): Promise<number | null> {
    actionError.value = null
    try {
      const result = await api.deleteExperiment(id)
      if (current.value?.id === id) {
        cancelReplayAndCleanup()
        current.value = null
        resetLiveBuffers()
        clearPlaybackState()
        disconnect()
      }
      await loadList()
      return result.freedBytes
    } catch (error) {
      actionError.value = error instanceof ApiError ? error.message : '删除实验失败。'
      return null
    }
  }

  return {
    summaries,
    listLoading,
    listError,
    backendReachable,
    current,
    currentLoading,
    currentError,
    liveState,
    liveMetrics,
    metricSamples,
    events,
    encounterAlerts,
    connectionState,
    actionPending,
    actionError,
    trails,
    snapshotBuffer,
    trailVersion,
    playbackBufferRef,
    playbackMode,
    playbackPrecision,
    cursorStep,
    cursorTimeSeconds,
    selectedEventId,
    historyLoading,
    historyError,
    activeReplayJob,
    availableFromStep,
    availableToStep,
    archiveSampleStride,
    plannedEndStep,
    isReviewing,
    displayState,
    trailCutoffStep,
    queued,
    running,
    totalStorageBytes,
    currentStatus,
    can,
    loadList,
    loadExperiment,
    connect,
    disconnect,
    dismissEncounter,
    createExperiment,
    submitAction,
    submitActionFor,
    updateQueuedConfig,
    reorderQueue,
    deleteExperiment,
    resetLiveBuffers,
    upsertEvent,
    loadHistoryOverview,
    loadFocusRange,
    scheduleFocusRange,
    requestExactStep,
    locateEvent,
    enterReview,
    seekCursorFromCache,
    beginReviewScrub,
    maybeAutoReturnToLive,
    jumpByPercent,
    startReviewPlayback,
    pauseReviewPlayback,
    returnToLive,
    cancelReplayAndCleanup,
    clearPlaybackState,
  }
})

/** PlaybackBuffer 插值点转回契约 SimulationState，供 Canvas 显示。 */
function interpolatedToState(point: InterpolatedPoint): SimulationState {
  return {
    step: point.step,
    simulationTimeSeconds: point.simulationTimeSeconds,
    bodies: point.bodies,
  }
}
