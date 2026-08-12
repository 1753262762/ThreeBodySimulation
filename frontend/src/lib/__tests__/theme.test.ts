import { afterEach, describe, expect, it } from 'vitest'
import {
  applyThemeToDocument,
  getChartPalette,
  isThemePreference,
  resolveTheme,
  THEME_META_COLORS,
} from '../theme'

describe('theme 解析', () => {
  it('显式浅色/深色不随系统变化', () => {
    expect(resolveTheme('light', true)).toBe('light')
    expect(resolveTheme('dark', false)).toBe('dark')
  })

  it('system 跟随系统偏好', () => {
    expect(resolveTheme('system', true)).toBe('dark')
    expect(resolveTheme('system', false)).toBe('light')
  })

  it('isThemePreference 只接受三态', () => {
    expect(isThemePreference('system')).toBe(true)
    expect(isThemePreference('light')).toBe(true)
    expect(isThemePreference('dark')).toBe(true)
    expect(isThemePreference('blue')).toBe(false)
    expect(isThemePreference(undefined)).toBe(false)
    expect(isThemePreference('')).toBe(false)
  })
})

describe('applyThemeToDocument', () => {
  afterEach(() => {
    document.documentElement.removeAttribute('data-theme')
    document.head.querySelector('meta[name="theme-color"]')?.remove()
  })

  it('写入 data-theme、colorScheme 与主题色 meta', () => {
    applyThemeToDocument('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(document.documentElement.style.colorScheme).toBe('dark')
    const meta = document.head.querySelector('meta[name="theme-color"]') as HTMLMetaElement | null
    expect(meta).not.toBeNull()
    expect(meta?.getAttribute('content')).toBe(THEME_META_COLORS.dark)

    applyThemeToDocument('light')
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(document.documentElement.style.colorScheme).toBe('light')
    expect(meta?.getAttribute('content')).toBe(THEME_META_COLORS.light)
  })
})

describe('getChartPalette', () => {
  it('浅深主题返回不同色板', () => {
    const light = getChartPalette('light')
    const dark = getChartPalette('dark')
    expect(light.axisText).not.toBe(dark.axisText)
    expect(light.tooltipBackground).toContain('255, 255, 255')
    expect(dark.tooltipBackground).toContain('12, 22, 38')
  })
})
