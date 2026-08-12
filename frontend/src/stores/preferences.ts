/**
 * 全局界面偏好：单位制、画布显示开关与主题。
 * 单位制只影响展示与表单，Store 内部与 API 始终使用 SI。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { UnitSystem } from '../lib/units'
import {
  applyThemeToDocument,
  isThemePreference,
  resolveTheme,
  systemPrefersDark,
  type ResolvedTheme,
  type ThemePreference,
} from '../lib/theme'

export type ProjectionPlane = 'XY' | 'XZ' | 'YZ'

/** 观察模式：FREE 自由视角；CENTER_OF_MASS 质心跟随；FOLLOW_BODY 天体跟随；AUTO_FIT 自动适应。 */
export type CameraMode = 'FREE' | 'CENTER_OF_MASS' | 'FOLLOW_BODY' | 'AUTO_FIT'

const STORAGE_KEY = 'three-body-lab.preferences.v1'

interface PersistedPreferences {
  unitSystem?: UnitSystem
  projection?: ProjectionPlane
  cameraMode?: CameraMode
  showTrails?: boolean
  showLabels?: boolean
  showGrid?: boolean
  showPerformanceHud?: boolean
  theme?: ThemePreference
}

function readPersisted(): PersistedPreferences {
  if (typeof localStorage === 'undefined') return {}
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as PersistedPreferences) : {}
  } catch {
    return {}
  }
}

export const usePreferencesStore = defineStore('preferences', () => {
  const persisted = readPersisted()

  const unitSystem = ref<UnitSystem>(persisted.unitSystem ?? 'ASTRONOMICAL')
  const projection = ref<ProjectionPlane>(persisted.projection ?? 'XY')
  /** FOLLOW_BODY 只保存在当前实验会话，不持久化。 */
  const cameraMode = ref<CameraMode>(persisted.cameraMode ?? 'FREE')
  const followBodyId = ref<string | null>(null)
  const showTrails = ref(persisted.showTrails ?? true)
  const showLabels = ref(persisted.showLabels ?? true)
  const showGrid = ref(persisted.showGrid ?? true)
  const showPerformanceHud = ref(persisted.showPerformanceHud ?? false)

  // 旧 localStorage 没有主题字段时按 system 迁移。
  const themePreference = ref<ThemePreference>(isThemePreference(persisted.theme) ? persisted.theme : 'system')
  const resolvedTheme = ref<ResolvedTheme>(resolveTheme(themePreference.value, systemPrefersDark()))

  let themeChangeListener: ((event: MediaQueryListEvent) => void) | null = null

  function persist(): void {
    if (typeof localStorage === 'undefined') return
    try {
      localStorage.setItem(
        STORAGE_KEY,
        JSON.stringify({
          unitSystem: unitSystem.value,
          projection: projection.value,
          cameraMode: cameraMode.value,
          showTrails: showTrails.value,
          showLabels: showLabels.value,
          showGrid: showGrid.value,
          showPerformanceHud: showPerformanceHud.value,
          theme: themePreference.value,
        } satisfies PersistedPreferences),
      )
    } catch {
      // 隐私模式下写入失败可以忽略。
    }
  }

  /** system 偏好时监听 prefers-color-scheme 变化，显式浅深色不跟随系统。 */
  function syncThemeListener(): void {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
    if (themeChangeListener) {
      window.matchMedia('(prefers-color-scheme: dark)').removeEventListener('change', themeChangeListener)
      themeChangeListener = null
    }
    if (themePreference.value === 'system') {
      themeChangeListener = () => {
        resolvedTheme.value = resolveTheme('system', systemPrefersDark())
        applyThemeToDocument(resolvedTheme.value)
      }
      window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', themeChangeListener)
    }
  }

  function applyResolvedTheme(): void {
    resolvedTheme.value = resolveTheme(themePreference.value, systemPrefersDark())
    applyThemeToDocument(resolvedTheme.value)
  }

  function setThemePreference(preference: ThemePreference): void {
    themePreference.value = preference
    applyResolvedTheme()
    syncThemeListener()
    persist()
  }

  /** Pinia 激活后、Vue mount 前调用一次，并注册 system 监听。 */
  function initializeTheme(): void {
    applyResolvedTheme()
    syncThemeListener()
  }

  /** 卸载时解除 system 监听，主要用于测试清理。 */
  function disposeTheme(): void {
    if (themeChangeListener && typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
      window.matchMedia('(prefers-color-scheme: dark)').removeEventListener('change', themeChangeListener)
      themeChangeListener = null
    }
  }

  function setUnitSystem(system: UnitSystem): void {
    unitSystem.value = system
    persist()
  }

  function setProjection(plane: ProjectionPlane): void {
    projection.value = plane
    persist()
  }

  /** 切换观察模式；没有有效目标时不得持久化不可用的 FOLLOW_BODY。 */
  function setCameraMode(mode: CameraMode): void {
    cameraMode.value = mode
    if (mode !== 'FOLLOW_BODY') {
      persist()
    }
  }

  /** 跟随目标只保存在当前实验会话；目标不存在或切换实验时回退 FREE。 */
  function setFollowBody(bodyId: string | null): void {
    followBodyId.value = bodyId
    if (!bodyId) {
      cameraMode.value = 'FREE'
    }
  }

  function toggleTrails(value?: boolean): void {
    showTrails.value = value ?? !showTrails.value
    persist()
  }

  function toggleLabels(value?: boolean): void {
    showLabels.value = value ?? !showLabels.value
    persist()
  }

  function toggleGrid(value?: boolean): void {
    showGrid.value = value ?? !showGrid.value
    persist()
  }

  function togglePerformanceHud(value?: boolean): void {
    showPerformanceHud.value = value ?? !showPerformanceHud.value
    persist()
  }

  return {
    unitSystem,
    projection,
    cameraMode,
    followBodyId,
    showTrails,
    showLabels,
    showGrid,
    showPerformanceHud,
    themePreference,
    resolvedTheme,
    setThemePreference,
    initializeTheme,
    disposeTheme,
    setUnitSystem,
    setProjection,
    setCameraMode,
    setFollowBody,
    toggleTrails,
    toggleLabels,
    toggleGrid,
    togglePerformanceHud,
  }
})
