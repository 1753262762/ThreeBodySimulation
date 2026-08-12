import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { usePreferencesStore } from '../../stores/preferences'
import ThemeSelector from '../ThemeSelector.vue'

describe('ThemeSelector', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('渲染三态并默认选中跟随系统', () => {
    const wrapper = mount(ThemeSelector)
    const buttons = wrapper.findAll('button')
    expect(buttons).toHaveLength(3)
    const active = buttons.find((button) => button.classes().includes('active'))
    expect(active?.text()).toBe('跟随系统')
  })

  it('点击深色后更新偏好并持久化', async () => {
    const preferences = usePreferencesStore()
    const wrapper = mount(ThemeSelector)
    const darkButton = wrapper.findAll('button').find((button) => button.text() === '深色')
    await darkButton!.trigger('click')
    expect(preferences.themePreference).toBe('dark')
    expect(preferences.resolvedTheme).toBe('dark')
    const saved = JSON.parse(localStorage.getItem('three-body-lab.preferences.v1') ?? '{}') as { theme?: string }
    expect(saved.theme).toBe('dark')
    expect(wrapper.find('button[aria-pressed="true"]')?.text()).toBe('深色')
  })
})
