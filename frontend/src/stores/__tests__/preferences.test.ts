import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { usePreferencesStore } from '../preferences'

describe('preferences 性能 HUD', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('默认关闭并持久化切换状态', () => {
    const preferences = usePreferencesStore()
    expect(preferences.showPerformanceHud).toBe(false)
    preferences.togglePerformanceHud(true)
    expect(preferences.showPerformanceHud).toBe(true)
    const saved = JSON.parse(localStorage.getItem('three-body-lab.preferences.v1') ?? '{}') as { showPerformanceHud?: boolean }
    expect(saved.showPerformanceHud).toBe(true)
  })

  it('新 store 从持久化值恢复', () => {
    localStorage.setItem('three-body-lab.preferences.v1', JSON.stringify({ showPerformanceHud: true }))
    const preferences = usePreferencesStore()
    expect(preferences.showPerformanceHud).toBe(true)
  })
})
