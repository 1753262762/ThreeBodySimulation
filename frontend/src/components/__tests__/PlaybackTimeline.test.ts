import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Experiment } from '../../contracts'
import { useExperimentsStore } from '../../stores/experiments'
import PlaybackTimeline from '../PlaybackTimeline.vue'

function experiment(status: 'RUNNING' | 'PAUSED' = 'RUNNING'): Experiment {
  return {
    id: 'experiment-1', name: 'Timeline', status,
    queuePosition: 0, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', bodyCount: 0,
    progress: {
      step: 100, simulationTimeSeconds: 10, maxSteps: 1_000,
      targetSimulationTimeSeconds: null, completionRatio: 0.1,
      stepsPerSecond: null, estimatedRemainingSteps: 900,
    },
    config: {
      timeStepSeconds: 0.1, maxSteps: 1_000, targetSimulationTimeSeconds: null,
      gravitationalConstant: 6.6743e-11, softeningLengthMeters: 1, bodies: [],
    },
    trajectory: { sampleStride: 10, sampleCount: 3, pointLimit: 2_000, liveWindowSize: 8_000 },
    events: [],
  }
}

describe('PlaybackTimeline', () => {
  beforeEach(() => setActivePinia(createPinia()))
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('实时模式使用同一主按钮暂停模拟且不渲染旧实验进度条', async () => {
    const store = useExperimentsStore()
    store.current = experiment('RUNNING')
    store.availableFromStep = 0
    const submit = vi.spyOn(store, 'submitAction').mockResolvedValue(true)
    const wrapper = mount(PlaybackTimeline)

    expect(wrapper.find('[role="progressbar"]').exists()).toBe(false)
    await wrapper.get('[aria-label="暂停模拟"]').trigger('click')
    expect(submit).toHaveBeenCalledWith('PAUSE')
  })

  it('历史模式切换为 Replay 控制并呈现倍速与返回实时', async () => {
    const store = useExperimentsStore()
    store.current = experiment('RUNNING')
    store.playbackMode = 'REVIEW_PAUSED'
    store.cursorStep = 40
    store.cursorTimeSeconds = 4
    const play = vi.spyOn(store, 'startReviewPlayback')
    const wrapper = mount(PlaybackTimeline)

    expect(wrapper.get('[aria-label="Replay 倍速"]').findAll('option').map((item) => item.text()))
      .toEqual(['0.25×', '0.5×', '1×', '2×', '4×'])
    expect(wrapper.get('.return-live').text()).toBe('返回实时')
    await wrapper.get('[aria-label="播放历史"]').trigger('click')
    expect(play).toHaveBeenCalledOnce()
  })

  it('只有时间线根节点获得焦点时响应 Space 与方向键', async () => {
    const store = useExperimentsStore()
    store.current = experiment('RUNNING')
    const submit = vi.spyOn(store, 'submitAction').mockResolvedValue(true)
    const stepFrame = vi.spyOn(store, 'stepReviewFrame')
    const wrapper = mount(PlaybackTimeline)
    const timeline = wrapper.get('[aria-label="Replay 时间线"]')

    await timeline.trigger('keydown', { code: 'Space' })
    await timeline.trigger('keydown', { key: 'ArrowLeft' })
    expect(submit).toHaveBeenCalledWith('PAUSE')
    expect(stepFrame).toHaveBeenCalledWith(-1)
  })

  it('无 RAF 时立即跟随有效 pointer，并在取消或 capture 丢失时结束拖动', async () => {
    vi.stubGlobal('requestAnimationFrame', undefined)
    const store = useExperimentsStore()
    store.current = experiment('PAUSED')
    store.availableFromStep = 0
    const begin = vi.spyOn(store, 'beginReviewScrub')
    const seek = vi.spyOn(store, 'seekCursorFromCache')
    const end = vi.spyOn(store, 'endReviewScrub')
    const wrapper = mount(PlaybackTimeline)
    const track = wrapper.get('[role="slider"]')
    vi.spyOn(track.element, 'getBoundingClientRect').mockReturnValue({
      left: 0, right: 100, top: 0, bottom: 38, width: 100, height: 38, x: 0, y: 0,
      toJSON: () => ({}),
    })

    const dispatchPointer = (type: string, clientX: number) => {
      const event = new MouseEvent(type, { bubbles: true, button: 0, clientX })
      Object.defineProperty(event, 'pointerId', { value: 7 })
      track.element.dispatchEvent(event)
    }
    dispatchPointer('pointerdown', 100)
    dispatchPointer('pointermove', 40)
    dispatchPointer('lostpointercapture', 40)

    expect(begin).toHaveBeenCalledWith(100)
    expect(seek).toHaveBeenCalledWith(40)
    expect(end).toHaveBeenCalledOnce()

    dispatchPointer('pointerdown', 80)
    dispatchPointer('pointercancel', 60)
    expect(begin).toHaveBeenLastCalledWith(80)
    expect(end).toHaveBeenCalledTimes(2)
  })
})
