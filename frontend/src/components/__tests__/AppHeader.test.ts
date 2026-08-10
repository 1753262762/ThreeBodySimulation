import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { useExperimentsStore } from '../../stores/experiments'
import AppHeader from '../AppHeader.vue'

describe('AppHeader', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('REST 可用但尚未订阅实验时显示后端已连接', () => {
    const store = useExperimentsStore()
    store.backendReachable = true
    store.connectionState = 'IDLE'

    const wrapper = mount(AppHeader, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })

    expect(wrapper.text()).toContain('后端已连接')
    expect(wrapper.text()).not.toContain('未连接')
    expect(wrapper.get('.connection-badge').classes()).toContain('is-open')
  })

  it('WebSocket 打开后显示实时连接', () => {
    const store = useExperimentsStore()
    store.backendReachable = true
    store.connectionState = 'OPEN'

    const wrapper = mount(AppHeader, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })

    expect(wrapper.text()).toContain('实时连接')
  })
})
