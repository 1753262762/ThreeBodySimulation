import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ExperimentSummary } from '../../contracts'
import { useExperimentsStore } from '../../stores/experiments'
import QueuePanel from '../QueuePanel.vue'

function summary(id: string, name: string): ExperimentSummary {
  return {
    id,
    name,
    status: 'QUEUED',
    queuePosition: 0,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    startedAt: null,
    completedAt: null,
    bodyCount: 3,
    progress: {
      step: 0,
      simulationTimeSeconds: 0,
      maxSteps: 100,
      targetSimulationTimeSeconds: null,
      completionRatio: 0,
      stepsPerSecond: null,
      estimatedRemainingSteps: 100,
    },
    endReason: null,
    storageBytes: 0,
    errorCode: null,
  }
}

describe('QueuePanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('把卡片实验 ID 传给动作方法', async () => {
    const store = useExperimentsStore()
    store.summaries = [summary('experiment-1', '实验一'), summary('experiment-2', '实验二')]
    const submitActionFor = vi.spyOn(store, 'submitActionFor').mockResolvedValue(true)
    const wrapper = mount(QueuePanel, {
      global: {
        stubs: {
          RouterLink: { template: '<a><slot /></a>' },
        },
      },
    })

    const secondCard = wrapper.findAll('.queue-item')[1]
    const cancel = secondCard.findAll('button').find((button) => button.text() === '取消')
    expect(cancel).toBeDefined()
    await cancel!.trigger('click')

    expect(submitActionFor).toHaveBeenCalledWith('experiment-2', 'CANCEL')
  })

  it('排序入口不宣称支持拖拽', () => {
    const wrapper = mount(QueuePanel, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    expect(wrapper.text()).toContain('调整顺序')
    expect(wrapper.text()).not.toContain('拖拽排序')
  })
})
