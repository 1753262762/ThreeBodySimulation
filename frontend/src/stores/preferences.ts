/**
 * 全局界面偏好：单位制、画布显示开关。
 * 单位制只影响展示与表单，Store 内部与 API 始终使用 SI。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { UnitSystem } from '../lib/units'

export type ProjectionPlane = 'XY' | 'XZ' | 'YZ'

const STORAGE_KEY = 'three-body-lab.preferences.v1'

interface PersistedPreferences {
  unitSystem?: UnitSystem
  projection?: ProjectionPlane
  showTrails?: boolean
  showLabels?: boolean
  showGrid?: boolean
  showPerformanceHud?: boolean
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
  const showTrails = ref(persisted.showTrails ?? true)
  const showLabels = ref(persisted.showLabels ?? true)
  const showGrid = ref(persisted.showGrid ?? true)
  const showPerformanceHud = ref(persisted.showPerformanceHud ?? false)

  function persist(): void {
    if (typeof localStorage === 'undefined') return
    try {
      localStorage.setItem(
        STORAGE_KEY,
        JSON.stringify({
          unitSystem: unitSystem.value,
          projection: projection.value,
          showTrails: showTrails.value,
          showLabels: showLabels.value,
          showGrid: showGrid.value,
          showPerformanceHud: showPerformanceHud.value,
        } satisfies PersistedPreferences),
      )
    } catch {
      // 隐私模式下写入失败可以忽略。
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
    showTrails,
    showLabels,
    showGrid,
    showPerformanceHud,
    setUnitSystem,
    setProjection,
    toggleTrails,
    toggleLabels,
    toggleGrid,
    togglePerformanceHud,
  }
})
