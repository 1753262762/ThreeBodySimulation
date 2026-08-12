/**
 * 主题解析与应用。
 *
 * 主题偏好固定为 system / light / dark，默认 system 并持久化；
 * 解析后的主题只有 light / dark 两种。本模块只提供纯函数与文档副作用，
 * 状态与 matchMedia 监听生命周期由 preferences store 管理。
 */
export type ThemePreference = 'system' | 'light' | 'dark'
export type ResolvedTheme = 'light' | 'dark'

export const THEME_PREFERENCE_VALUES: readonly ThemePreference[] = ['system', 'light', 'dark']

export const THEME_PREFERENCE_LABELS: Record<ThemePreference, string> = {
  system: '跟随系统',
  light: '浅色',
  dark: '深色',
}

export function isThemePreference(value: unknown): value is ThemePreference {
  return typeof value === 'string' && (THEME_PREFERENCE_VALUES as readonly string[]).includes(value)
}

/** 解析偏好；system 需要传入系统是否偏好深色。 */
export function resolveTheme(preference: ThemePreference, systemPrefersDark: boolean): ResolvedTheme {
  if (preference === 'light') return 'light'
  if (preference === 'dark') return 'dark'
  return systemPrefersDark ? 'dark' : 'light'
}

/** 查询系统深色偏好；非浏览器环境按浅色处理。 */
export function systemPrefersDark(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

/** 各主题的浏览器主题色，用于 <meta name="theme-color">。 */
export const THEME_META_COLORS: Record<ResolvedTheme, string> = {
  light: '#f4f7fb',
  dark: '#0a1220',
}

/**
 * 把解析后的主题写到 <html data-theme> 与 color-scheme，并更新主题色 meta。
 * 在 Vue mount 前调用一次可减少首屏闪烁。纯副作用，无状态。
 */
export function applyThemeToDocument(theme: ResolvedTheme): void {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  root.dataset.theme = theme
  root.style.colorScheme = theme
  let meta = document.querySelector('meta[name="theme-color"]')
  if (!meta) {
    meta = document.createElement('meta')
    meta.setAttribute('name', 'theme-color')
    document.head.appendChild(meta)
  }
  meta.setAttribute('content', THEME_META_COLORS[theme])
}

/** ECharts 语义色板：浅深主题独立定义，主题切换时以 notMerge 完整刷新。 */
export interface ChartPalette {
  axisText: string
  axisLine: string
  gridLine: string
  legendText: string
  tooltipBackground: string
  tooltipBorder: string
  tooltipText: string
}

export function getChartPalette(theme: ResolvedTheme): ChartPalette {
  if (theme === 'light') {
    return {
      axisText: '#5b6b80',
      axisLine: '#c9d4e2',
      gridLine: 'rgba(140, 156, 178, 0.35)',
      legendText: '#6b7c93',
      tooltipBackground: 'rgba(255, 255, 255, 0.96)',
      tooltipBorder: '#c9d4e2',
      tooltipText: '#24313f',
    }
  }
  return {
    axisText: '#6d829e',
    axisLine: '#22324a',
    gridLine: 'rgba(34, 50, 74, 0.4)',
    legendText: '#8fa3bd',
    tooltipBackground: 'rgba(12, 22, 38, 0.95)',
    tooltipBorder: '#22324a',
    tooltipText: '#dfe9f5',
  }
}
/** Canvas 语义色板：背景、网格、轴线、HUD 文字随主题切换。 */
export interface CanvasPalette {
  background: string
  gridLine: string
  axisLine: string
  hudText: string
}

export function getCanvasPalette(theme: ResolvedTheme): CanvasPalette {
  if (theme === 'light') {
    return {
      background: '#eef3f9',
      gridLine: 'rgba(70, 90, 116, 0.25)',
      axisLine: 'rgba(70, 90, 116, 0.5)',
      hudText: 'rgba(70, 90, 116, 0.85)',
    }
  }
  return {
    background: '#05080f',
    gridLine: 'rgba(111, 137, 168, 0.22)',
    axisLine: 'rgba(111, 137, 168, 0.5)',
    hudText: 'rgba(141, 149, 162, 0.85)',
  }
}
