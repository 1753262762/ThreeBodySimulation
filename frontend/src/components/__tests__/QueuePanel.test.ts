import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ExperimentSummary } from '../../contracts'
import { useExperimentsStore } from '../../stores/experiments'
import AppTooltip from '../AppTooltip.vue'
import QueuePanel from '../QueuePanel.vue'

function summary(id: string, name: string, overrides: Partial<ExperimentSummary> = {}): ExperimentSummary {
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
    ...overrides,
  }
}

function mountPanel() {
  return mount(QueuePanel, {
    global: {
      stubs: {
        RouterLink: { template: '<a><slot /></a>' },
      },
    },
  })
}

function reorderNames(wrapper: ReturnType<typeof mountPanel>): string[] {
  return wrapper.findAll('.reorder-row').map((row) => row.get('.queue-name').text())
}

describe('QueuePanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('把卡片实验 ID 传给动作方法', async () => {
    const store = useExperimentsStore()
    store.summaries = [summary('experiment-1', '实验一'), summary('experiment-2', '实验二')]
    const submitActionFor = vi.spyOn(store, 'submitActionFor').mockResolvedValue(true)
    const wrapper = mountPanel()

    const secondCard = wrapper.findAll('.queue-item')[1]
    const cancel = secondCard.findAll('button').find((button) => button.text() === '取消')
    expect(cancel).toBeDefined()
    await cancel!.trigger('click')

    expect(submitActionFor).toHaveBeenCalledWith('experiment-2', 'CANCEL')
  })

  it('少于两个等待任务时禁用排序并提供原因提示', async () => {
    const store = useExperimentsStore()
    const wrapper = mountPanel()
    const toggle = wrapper.get<HTMLButtonElement>('.queue-reorder-toggle')

    expect(toggle.element.disabled).toBe(true)
    expect(wrapper.getComponent(AppTooltip).props('text')).toBe('至少需要两个等待任务')
    expect(wrapper.get('.tooltip-wrap').attributes('tabindex')).toBe('0')

    store.summaries = [summary('experiment-1', '实验一')]
    await wrapper.vm.$nextTick()
    expect(toggle.element.disabled).toBe(true)

    store.summaries.push(summary('experiment-2', '实验二'))
    await wrapper.vm.$nextTick()
    expect(toggle.element.disabled).toBe(false)
    expect(wrapper.getComponent(AppTooltip).props('text')).toBe('调整等待任务顺序')
  })

  it('通过上下按钮调整等待任务顺序并限制边界操作', async () => {
    const store = useExperimentsStore()
    store.summaries = [
      summary('experiment-1', '实验一'),
      summary('experiment-2', '实验二'),
      summary('experiment-3', '实验三'),
    ]
    const wrapper = mountPanel()

    await wrapper.get('.queue-reorder-toggle').trigger('click')
    expect(reorderNames(wrapper)).toEqual(['实验一', '实验二', '实验三'])
    expect(wrapper.findAll('.reorder-row')[0]!.findAll<HTMLButtonElement>('button')[0]!.element.disabled).toBe(true)
    expect(wrapper.findAll('.reorder-row')[2]!.findAll<HTMLButtonElement>('button')[1]!.element.disabled).toBe(true)

    await wrapper.findAll('.reorder-row')[1]!.findAll('button')[1]!.trigger('click')
    expect(reorderNames(wrapper)).toEqual(['实验一', '实验三', '实验二'])

    await wrapper.findAll('.reorder-row')[2]!.findAll('button')[0]!.trigger('click')
    expect(reorderNames(wrapper)).toEqual(['实验一', '实验二', '实验三'])
  })

  it('提交重排后的等待任务及其余实验完整顺序，成功后退出排序', async () => {
    const store = useExperimentsStore()
    store.summaries = [
      summary('queued-1', '等待一', { queuePosition: 1 }),
      summary('running', '运行中', { status: 'RUNNING', queuePosition: 0 }),
      summary('queued-2', '等待二', { queuePosition: 2 }),
      summary('completed', '已完成', { status: 'COMPLETED', queuePosition: 3 }),
    ]
    const reorderQueue = vi.spyOn(store, 'reorderQueue').mockResolvedValue(true)
    const wrapper = mountPanel()

    await wrapper.get('.queue-reorder-toggle').trigger('click')
    await wrapper.findAll('.reorder-row')[1]!.findAll('button')[0]!.trigger('click')
    await wrapper.get('.reorder-box .apply-button').trigger('click')

    await vi.waitFor(() => {
      expect(reorderQueue).toHaveBeenCalledWith(['queued-2', 'queued-1', 'running', 'completed'])
      expect(wrapper.find('.reorder-box').exists()).toBe(false)
    })
  })

  it('提交失败时保留排序界面，等待队列失效时自动退出', async () => {
    const store = useExperimentsStore()
    store.summaries = [summary('queued-1', '等待一'), summary('queued-2', '等待二')]
    const reorderQueue = vi.spyOn(store, 'reorderQueue').mockResolvedValue(false)
    const wrapper = mountPanel()

    await wrapper.get('.queue-reorder-toggle').trigger('click')
    await wrapper.get('.reorder-box .apply-button').trigger('click')
    await vi.waitFor(() => expect(reorderQueue).toHaveBeenCalledOnce())
    expect(wrapper.find('.reorder-box').exists()).toBe(true)

    store.summaries = [summary('queued-1', '等待一')]
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.reorder-box').exists()).toBe(false)
    expect(wrapper.get<HTMLButtonElement>('.queue-reorder-toggle').element.disabled).toBe(true)
  })
})
