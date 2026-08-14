import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import ParameterHelpBlock from '../ParameterHelpBlock.vue'

const help = {
  meaning: '参数含义。',
  impact: '参数作用与风险。',
  range: '参数调整方法。',
}

function dialog(): HTMLElement | null {
  return document.body.querySelector('[role="dialog"]')
}

describe('ParameterHelpBlock', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('点击帮助按钮后用系统模态框展示完整说明', async () => {
    const wrapper = mount(ParameterHelpBlock, {
      props: { title: '时间步长', help },
      attachTo: document.body,
    })

    expect(dialog()).toBeNull()
    await wrapper.get('button').trigger('click')

    const modal = dialog()
    expect(modal).not.toBeNull()
    expect(modal?.getAttribute('aria-modal')).toBe('true')
    expect(modal?.textContent).toContain('时间步长')
    expect(modal?.textContent).toContain(help.meaning)
    expect(modal?.textContent).toContain(help.impact)
    expect(modal?.textContent).toContain(help.range)

    const closeButton = modal?.querySelector<HTMLButtonElement>('[data-autofocus]')
    closeButton?.click()
    await wrapper.vm.$nextTick()
    expect(dialog()).toBeNull()
    wrapper.unmount()
  })

  it('Escape 关闭模态框并把焦点还给触发按钮', async () => {
    const wrapper = mount(ParameterHelpBlock, {
      props: { title: '软化长度', help },
      attachTo: document.body,
    })
    const trigger = wrapper.get<HTMLButtonElement>('button')
    await trigger.trigger('click')

    dialog()?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(dialog()).toBeNull()
    expect(document.activeElement).toBe(trigger.element)
    wrapper.unmount()
  })
})
