import { shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LabView from '../LabView.vue'

describe('LabView 监控区', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.spyOn(window, 'requestAnimationFrame').mockReturnValue(1)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('收起监控区时同时收起事件与诊断栏目', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: { template: '<div />' } }],
    })
    await router.push('/')
    await router.isReady()

    const wrapper = shallowMount(LabView, {
      global: {
        plugins: [router],
      },
    })

    expect(wrapper.findComponent({ name: 'EventPanel' }).exists()).toBe(true)

    const toggle = wrapper.get<HTMLButtonElement>('button.monitor-toggle-button')
    await toggle.trigger('click')

    expect(toggle.text()).toBe('展开监控区')
    expect(wrapper.findComponent({ name: 'EventPanel' }).exists()).toBe(false)

    await toggle.trigger('click')

    expect(toggle.text()).toBe('收起监控区')
    expect(wrapper.findComponent({ name: 'EventPanel' }).exists()).toBe(true)
  })
})
