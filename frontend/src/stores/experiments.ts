/**
 * 实验队列与实时联调。
 *
 * 关键约束：
 * - REST 是权威全量来源，WebSocket 只提供增量。
 * - 重连期间暂停增量应用，等 REST 全量同步完成再恢复。
 * - 轨迹按天体保留最近 LIVE_TRAIL_LIMIT 个三维点，切换投影时复用同一份数据。
 */
import { defineStore } from 'pinia'
import { computed, ref, shallowRef } from 'vue'
import {
  LIVE_TRAIL_LIMIT,
  isActionAllowed,
  type Experiment,
  type ExperimentAction,
  type ExperimentStatus,
  type ExperimentSummary,
  type Metrics,
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
  type NearEncounterPayload,
  type StatusPayload,
  type TrajectoryPayload,
} from '../lib/experimentSocket'

/** 图表使用的时间序列点，单位与契约一致（SI）。 */
export interface MetricSample {
  step: number
  simulationTimeSeconds: number
  totalEnergyJoules: number
  relativeEnergyDrift: number
  angularMomentumMagnitude: number
  minimumPairDistanceMeters: number
}

const MAX_METRIC_SAMPLES = 3000

export const useExperimentsStore = defineStore('experiments', () => {
  const summaries = ref<ExperimentSummary[]>([])
  const listLoading = ref(false)
  const listError = ref<string | null>(null)

  const current = ref<Experiment | null>(null)
  const currentLoading = ref(false)
  const currentError = ref<string | null>(null)

  const liveState = ref<SimulationState | null>(null)
  const liveMetrics = ref<Metrics | null>(null)
  const metricSamples = ref<MetricSample[]>([])
  const events = ref<SimulationEvent[]>([])
  /** 近距离事件的非阻塞提示队列，界面消费后移除。 */
  const encounterAlerts = ref<NearEncounterPayload[]>([])

  const connectionState = ref<ConnectionState>('IDLE')
  const actionPending = ref<ExperimentAction | null>(null)
  const actionError = ref<string | null>(null)

  /** 轨迹缓冲不需要深响应，使用 shallowRef 手动触发更新。 */
  const trails = shallowRef<Map<string, number[]>>(new Map())
  const trailVersion = ref(0)

  let socket: ExperimentSocket | null = null
  /** 重连后 REST 全量同步未完成时挂起增量。 */
  let resyncing = false

  const queued = computed(() => summaries.value.filter((item) => item.status === 'QUEUED'))
  const running = computed(() => summaries.value.find((item) => item.status === 'RUNNING') ?? null)
  const totalStorageBytes = computed(() =>
    summaries.value.reduce((sum, item) => sum + (item.storageBytes ?? 0), 0),
  )

  const currentStatus = computed<ExperimentStatus | null>(() => current.value?.status ?? null)

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
    trails.value = new Map()
    trailVersion.value += 1
  }

  async function loadList(): Promise<void> {
    listLoading.value = true
    listError.value = null
    try {
      summaries.value = await api.listExperiments()
    } catch (error) {
      listError.value = error instanceof ApiError ? error.message : '加载实验列表失败。'
    } finally {
      listLoading.value = false
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
      }
      if (experiment.state) {
        liveState.value = experiment.state
      }
      if (experiment.metrics) {
        liveMetrics.value = experiment.metrics
        appendMetricSample(experiment.metrics, experiment.progress.step, experiment.progress.simulationTimeSeconds)
      }
      events.value = experiment.events ?? []
      if (typeof experiment.lastSequence === 'number') {
        socket?.setSequenceFloor(experiment.lastSequence)
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

  function pushTrailPoint(bodyId: string, x: number, y: number, z: number): void {
    let points = trails.value.get(bodyId)
    if (!points) {
      points = []
      trails.value.set(bodyId, points)
    }
    points.push(x, y, z)
    const limit = LIVE_TRAIL_LIMIT * 3
    if (points.length > limit) {
      points.splice(0, points.length - limit)
    }
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
          liveState.value = snapshotToState(payload)
          for (const body of payload.bodies) {
            pushTrailPoint(body.id, body.position.x, body.position.y, body.position.z)
          }
          trailVersion.value += 1
        },
        onTrajectory: (payload: TrajectoryPayload) => {
          if (resyncing) return
          for (const point of payload.points) {
            for (const body of point.bodies) {
              pushTrailPoint(body.id, body.position.x, body.position.y, body.position.z)
            }
          }
          trailVersion.value += 1
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
        onNearEncounter: (payload) => {
          if (resyncing) return
          encounterAlerts.value = [...encounterAlerts.value, payload].slice(-20)
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

  function dismissEncounter(index: number): void {
    encounterAlerts.value = encounterAlerts.value.filter((_, i) => i !== index)
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

  async function submitAction(action: ExperimentAction, config?: SimulationConfig): Promise<boolean> {
    const experiment = current.value
    if (!experiment) return false
    actionPending.value = action
    actionError.value = null
    try {
      const updated = await api.submitAction(experiment.id, {
        action,
        // 契约要求：只有 RESTART 可携带配置，其他动作必须为 null。
        config: action === 'RESTART' ? (config ?? null) : null,
      })
      current.value = updated
      if (action === 'RESTART') {
        resetLiveBuffers()
      }
      await loadList()
      return true
    } catch (error) {
      if (error instanceof ApiError) {
        actionError.value = error.message
        // 状态冲突说明本地视图已过期，立即全量刷新。
        if (error.isConflict) {
          await loadExperiment(experiment.id, { keepBuffers: true }).catch(() => undefined)
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
        current.value = null
        resetLiveBuffers()
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
    trailVersion,
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
    updateQueuedConfig,
    reorderQueue,
    deleteExperiment,
    resetLiveBuffers,
  }
})