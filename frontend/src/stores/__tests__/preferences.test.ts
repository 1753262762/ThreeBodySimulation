import { beforeEach, describe, expect, it, vi } from 'vitest'
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


describe('preferences 主题', () => {
  beforeEach(() => {
    document.documentElement.removeAttribute('data-theme')
    localStorage.clear()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('默认跟随系统并应用到文档', () => {
    const preferences = usePreferencesStore()
    preferences.initializeTheme()
    expect(preferences.themePreference).toBe('system')
    expect(document.documentElement.dataset.theme).toBe(preferences.resolvedTheme)
  })

  it('旧 localStorage 没有主题字段时迁移为 system', () => {
    localStorage.setItem('three-body-lab.preferences.v1', JSON.stringify({ unitSystem: 'SI' }))
    const preferences = usePreferencesStore()
    expect(preferences.themePreference).toBe('system')
  })

  it('显式浅深色设置会持久化并应用到文档', () => {
    const preferences = usePreferencesStore()
    preferences.setThemePreference('light')
    expect(preferences.resolvedTheme).toBe('light')
    expect(document.documentElement.dataset.theme).toBe('light')
    const saved = JSON.parse(localStorage.getItem('three-body-lab.preferences.v1') ?? '{}') as { theme?: string }
    expect(saved.theme).toBe('light')
  })

  it('system 模式监听 prefers-color-scheme 变化', () => {
    const listeners = new Set<(event: MediaQueryListEvent) => void>()
    let prefersDark = true
    const mql = {
      get matches() {
        return prefersDark
      },
      media: '(prefers-color-scheme: dark)',
      addEventListener: (_: string, cb: (event: MediaQueryListEvent) => void) => listeners.add(cb),
      removeEventListener: (_: string, cb: (event: MediaQueryListEvent) => void) => listeners.delete(cb),
      addListener: () => undefined,
      removeListener: () => undefined,
      onchange: null,
      dispatchEvent: () => true,
    }
    vi.stubGlobal('matchMedia', vi.fn(() => mql))
    const preferences = usePreferencesStore()
    preferences.initializeTheme()
    expect(preferences.resolvedTheme).toBe('dark')

    // 模拟系统切换为浅色
    prefersDark = false
    listeners.forEach((cb) => cb({ matches: false } as MediaQueryListEvent))
    expect(preferences.resolvedTheme).toBe('light')
    expect(document.documentElement.dataset.theme).toBe('light')
    preferences.disposeTheme()
  })

  it('显式主题不随系统变化，也不注册监听', () => {
    const addEventListener = vi.fn()
    const removeEventListener = vi.fn()
    vi.stubGlobal('matchMedia', vi.fn(() => ({
      matches: true,
      media: '(prefers-color-scheme: dark)',
      addEventListener,
      removeEventListener,
      addListener: () => undefined,
      removeListener: () => undefined,
      onchange: null,
      dispatchEvent: () => true,
    })))
    const preferences = usePreferencesStore()
    preferences.setThemePreference('dark')
    expect(preferences.resolvedTheme).toBe('dark')
    preferences.setThemePreference('light')
    expect(preferences.resolvedTheme).toBe('light')
    expect(addEventListener).not.toHaveBeenCalled()
    preferences.disposeTheme()
  })
})
