import { createPinia, setActivePinia } from 'pinia'
import { markRaw } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Experiment, ReplayJob, SimulationState } from '../../contracts'
import { api } from '../../lib/apiClient'
import { PlaybackBuffer } from '../../lib/playbackBuffer'
import { EXACT_REPLAY_SETTLE_MS, useExperimentsStore } from '../experiments'

function state(step: number): SimulationState {
  return {
    step,
    simulationTimeSeconds: step,
    bodies: [{ id: 'a', position: { x: step, y: 0, z: 0 }, velocity: { x: 1, y: 0, z: 0 } }],
  }
}

function experiment(): Experiment {
  return {
    id: 'experiment-1',
    name: 'Replay test',
    status: 'PAUSED',
    queuePosition: 0,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    bodyCount: 1,
    progress: {
      step: 100,
      simulationTimeSeconds: 100,
      maxSteps: 1_000,
      targetSimulationTimeSeconds: null,
      completionRatio: 0.1,
      stepsPerSecond: null,
      estimatedRemainingSteps: 900,
    },
    config: {
      timeStepSeconds: 1,
      maxSteps: 1_000,
      targetSimulationTimeSeconds: null,
      softeningLengthMeters: 1,
      gravitationalConstant: 6.6743e-11,
      bodies: [{
        id: 'a', name: 'A', color: '#fff', massKg: 1,
        position: { x: 0, y: 0, z: 0 },
        velocity: { x: 0, y: 0, z: 0 },
      }],
    },
    trajectory: { sampleStride: 10, sampleCount: 3, pointLimit: 2_000, liveWindowSize: 8_000 },
    events: [],
  }
}

function completedJob(targetStep: number): ReplayJob {
  return {
    jobId: `job-${targetStep}`,
    experimentId: 'experiment-1',
    targetStep,
    status: 'COMPLETED',
    source: 'RECOMPUTED',
    baseStep: 0,
    completedSteps: targetStep,
    totalSteps: targetStep,
    progress: 1,
    result: state(targetStep),
    error: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    expiresAt: '2026-01-01T00:10:00Z',
  }
}

function prepareStore() {
  const store = useExperimentsStore()
  store.current = experiment()
  store.liveState = state(100)
  store.availableFromStep = 0
  store.availableToStep = 100
  store.archiveSampleStride = 10
  const buffer = markRaw(new PlaybackBuffer(['a']))
  buffer.replaceOverview([state(0), state(50), state(100)])
  store.playbackBufferRef = buffer
  return store
}

describe('experiments Replay control', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
    vi.spyOn(api, 'deleteReplayJob').mockResolvedValue()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('拖动期间不创建任务，停下 300ms 后只解析最终一步', async () => {
    const create = vi.spyOn(api, 'createReplayJob').mockImplementation(async (_id, target) => completedJob(target))
    const store = prepareStore()

    store.beginReviewScrub(70)
    store.seekCursorFromCache(60)
    store.seekCursorFromCache(40)
    expect(create).not.toHaveBeenCalled()

    store.settleReviewCursor()
    await vi.advanceTimersByTimeAsync(EXACT_REPLAY_SETTLE_MS - 1)
    expect(create).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(create).toHaveBeenCalledTimes(1)
    expect(create).toHaveBeenCalledWith('experiment-1', 40)
  })

  it('过期精确结果不会覆盖新的播放头', async () => {
    let resolveFirst!: (job: ReplayJob) => void
    let resolveSecond!: (job: ReplayJob) => void
    const firstResult = new Promise<ReplayJob>((resolve) => { resolveFirst = resolve })
    const secondResult = new Promise<ReplayJob>((resolve) => { resolveSecond = resolve })
    vi.spyOn(api, 'createReplayJob').mockImplementation((_id, target) => target === 20 ? firstResult : secondResult)
    const store = prepareStore()
    store.playbackMode = 'REVIEW_PAUSED'

    store.cursorStep = 20
    const first = store.requestExactStep('experiment-1', 20)
    store.cursorStep = 30
    const second = store.requestExactStep('experiment-1', 30)
    resolveFirst(completedJob(20))
    await first
    expect(store.cursorStep).toBe(30)
    resolveSecond(completedJob(30))
    await second
    expect(store.cursorStep).toBe(30)
  })

  it('单帧按归档步长移动，到最右端自动返回实时', () => {
    vi.spyOn(api, 'createReplayJob').mockResolvedValue(completedJob(90))
    const store = prepareStore()

    store.stepReviewFrame(-1)
    expect(store.playbackMode).toBe('REVIEW_PAUSED')
    expect(store.cursorStep).toBe(90)
    store.stepReviewFrame(1)
    expect(store.playbackMode).toBe('LIVE')
  })

  it('RAF 按真实经过时间和倍速推进，到右端自动返回实时', () => {
    const frames: FrameRequestCallback[] = []
    vi.stubGlobal('requestAnimationFrame', vi.fn((callback: FrameRequestCallback) => {
      frames.push(callback)
      return frames.length
    }))
    vi.stubGlobal('cancelAnimationFrame', vi.fn())
    const store = prepareStore()
    store.playbackMode = 'REVIEW_PAUSED'
    store.cursorStep = 0
    store.setReplayRate(2)

    store.startReviewPlayback()
    expect(store.playbackMode).toBe('REVIEW_PLAYING')
    frames.shift()?.(0)
    frames.shift()?.(1_000)
    expect(store.cursorStep).toBe(3)
    frames.shift()?.(31_000)
    expect(store.playbackMode).toBe('LIVE')
  })
})
