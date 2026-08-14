import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { SimulationEvent } from '../../contracts'
import EventPanel from '../EventPanel.vue'

const event = {
  sequence: 7,
  eventId: 'event-7',
  type: 'NEAR_ENCOUNTER',
  phase: 'ENTER',
  simulationTimeSeconds: 12,
  message: '天体进入近遇阈值。',
  bodyIds: ['body-a', 'body-b'],
  triggerDistanceMeters: 100,
  closestDistanceMeters: 80,
  thresholdMeters: 120,
  closestSimulationTimeSeconds: 13,
} as SimulationEvent

let wrapper: VueWrapper | null = null

function dialog(): HTMLElement | null {
  return document.body.querySelector('[role="dialog"]')
}

async function mountAndOpen(): Promise<{ mounted: VueWrapper; trigger: HTMLButtonElement }> {
  wrapper = mount(EventPanel, {
    props: { events: [event], selectedEventId: null },
    attachTo: document.body,
  })
  const trigger = wrapper.get<HTMLButtonElement>('button.event-panel-expand').element
  trigger.click()
  await wrapper.vm.$nextTick()
  return { mounted: wrapper, trigger }
}

describe('EventPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    document.body.innerHTML = ''
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    document.body.innerHTML = ''
  })

  it('从紧凑面板打开系统风格诊断弹窗并展示事件数量', async () => {
    const { trigger } = await mountAndOpen()
    const modal = dialog()

    expect(modal).not.toBeNull()
    expect(modal?.getAttribute('aria-modal')).toBe('true')
    expect(modal?.textContent).toContain('事件与诊断')
    expect(modal?.textContent).toContain('1 条')
    expect(trigger.textContent).toBe('关闭')
    expect(document.activeElement).toBe(trigger)
  })

  it('支持关闭按钮、遮罩和 Escape 关闭并恢复触发按钮焦点', async () => {
    const { mounted, trigger } = await mountAndOpen()

    dialog()?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await mounted.vm.$nextTick()
    await mounted.vm.$nextTick()
    expect(dialog()).toBeNull()
    expect(document.activeElement).toBe(trigger)

    trigger.click()
    await mounted.vm.$nextTick()
    document.body.querySelector<HTMLElement>('.event-panel-host.is-expanded')?.click()
    await mounted.vm.$nextTick()
    expect(dialog()).toBeNull()

    trigger.click()
    await mounted.vm.$nextTick()
    trigger.click()
    await mounted.vm.$nextTick()
    expect(dialog()).toBeNull()
  })

  it('在首尾控件间约束 Tab 焦点', async () => {
    const { trigger: close } = await mountAndOpen()
    const locate = document.body.querySelector<HTMLButtonElement>('button.event-locate') as HTMLButtonElement

    locate.focus()
    locate.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }))
    expect(document.activeElement).toBe(close)

    close.focus()
    close.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true }))
    expect(document.activeElement).toBe(locate)
  })

  it('定位事件后发送原事件并自动关闭弹窗', async () => {
    const { mounted } = await mountAndOpen()

    document.body.querySelector<HTMLButtonElement>('button.event-locate')?.click()
    await mounted.vm.$nextTick()
    expect(mounted.emitted('select')).toEqual([[event]])
    expect(dialog()).toBeNull()
  })
})
