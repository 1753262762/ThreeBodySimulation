import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AppTooltip from '../AppTooltip.vue'

function findTooltip(): HTMLElement | null {
  return document.body.querySelector('.app-tooltip') as HTMLElement | null
}

async function flush(): Promise<void> {
  await new Promise((resolve) => window.requestAnimationFrame(resolve))
}

function pointerEvent(type: string, x: number, y: number): PointerEvent {
  return new PointerEvent(type, { bubbles: true, clientX: x, clientY: y })
}

describe('AppTooltip', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  afterEach(() => {
    document.body.innerHTML = ''
    vi.restoreAllMocks()
  })

  it('pointerenter 打开，pointerleave 关闭', async () => {
    const wrapper = mount(AppTooltip, {
      props: { text: '说明文字' },
      slots: { default: '<button>触发</button>' },
      attachTo: document.body,
    })
    const trigger = wrapper.find('.tooltip-wrap')
    expect(findTooltip()).toBeNull()
    trigger.element.dispatchEvent(pointerEvent('pointerenter', 10, 10))
    await flush()
    const tooltip = findTooltip()
    expect(tooltip).not.toBeNull()
    expect(tooltip?.textContent).toContain('说明文字')
    expect(tooltip?.getAttribute('role')).toBe('tooltip')
    trigger.element.dispatchEvent(pointerEvent('pointerleave', 10, 10))
    await flush()
    expect(findTooltip()).toBeNull()
    wrapper.unmount()
  })

  it('通过 aria-describedby 关联触发节点与内容', async () => {
    const wrapper = mount(AppTooltip, {
      props: { text: '提示' },
      slots: { default: '<button>触发</button>' },
      attachTo: document.body,
    })
    const trigger = wrapper.find('.tooltip-wrap')
    trigger.element.dispatchEvent(pointerEvent('pointerenter', 10, 10))
    await flush()
    const describedBy = trigger.attributes('aria-describedby')
    const tooltip = findTooltip()
    expect(describedBy).toBeDefined()
    expect(tooltip?.id).toBe(describedBy)
    wrapper.unmount()
  })

  it('focusin 打开、Escape/blur 关闭', async () => {
    const wrapper = mount(AppTooltip, {
      props: { text: '提示' },
      slots: { default: '<button>触发</button>' },
      attachTo: document.body,
    })
    const trigger = wrapper.find('.tooltip-wrap')
    trigger.element.dispatchEvent(new FocusEvent('focusin', { bubbles: true }))
    await flush()
    expect(findTooltip()).not.toBeNull()
    trigger.element.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flush()
    expect(findTooltip()).toBeNull()
    trigger.element.dispatchEvent(new FocusEvent('focusin', { bubbles: true }))
    await flush()
    expect(findTooltip()).not.toBeNull()
    trigger.element.dispatchEvent(new FocusEvent('focusout', { bubbles: true }))
    await flush()
    expect(findTooltip()).toBeNull()
    wrapper.unmount()
  })

  it('focusable 包装为 disabled 触发器提供键盘可聚焦入口', () => {
    const wrapper = mount(AppTooltip, {
      props: { text: '提示', focusable: true },
      slots: { default: '<button disabled>复制</button>' },
    })
    expect(wrapper.find('.tooltip-wrap').attributes('tabindex')).toBe('0')
  })

  it('title 与 content 插槽可组合展示', async () => {
    const wrapper = mount(AppTooltip, {
      props: { title: '参数标题', text: '正文' },
      slots: { default: '<button>触发</button>' },
      attachTo: document.body,
    })
    wrapper.find('.tooltip-wrap').element.dispatchEvent(pointerEvent('pointerenter', 10, 10))
    await flush()
    const tooltip = findTooltip()
    expect(tooltip?.querySelector('.tooltip-title')?.textContent).toContain('参数标题')
    expect(tooltip?.textContent).toContain('正文')
    wrapper.unmount()
  })
})
