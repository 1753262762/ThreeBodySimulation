/**
 * 预设与参数草稿。
 *
 * 职责：维护当前编辑中的配置草稿、服务端校验结果，以及导入导出。
 * 草稿只在"应用并重新计算"时提交，符合交付计划的草稿语义。
 *
 * 校验语义：
 * - 本地 canSubmit 只由当前本地 ERROR 与提交中状态决定，旧服务端 ERROR 不锁死按钮；
 * - 任一草稿变化清空 serverValidation、校验错误、Warning 确认与已校验指纹；
 * - validateWithServer 返回完整 ValidationResult | null；
 * - Warning 需用户显式确认后才允许创建（warningsConfirmed 随配置指纹失效）。
 */
import { defineStore } from 'pinia'
import { computed, onScopeDispose, ref, watch } from 'vue'
import type {
  ExperimentRetryContext,
  GuidanceAction,
  Preset,
  PresetKey,
  SimulationConfig,
  ValidationIssue,
  ValidationResult,
} from '../contracts'
import { ApiError, api } from '../lib/apiClient'
import {
  configToDraft,
  convertDraftUnits,
  createBodyDraft,
  draftToConfig,
  duplicateBodyDraft,
  type ConfigDraft,
} from '../lib/configDraft'
import { MAX_BODY_COUNT, MIN_BODY_COUNT } from '../contracts'
import type { UnitSystem } from '../lib/units'
import {
  planComparisonExperiment,
  resolveStepLimit,
  type ComparisonExperimentPlan,
  type StepLimitResolution,
} from '../lib/comparisonExperiment'

/** 服务端不可用时也要能编辑参数，因此内置一份与契约示例一致的兜底配置。 */
const FALLBACK_CONFIG: SimulationConfig = {
  name: '自定义实验',
  bodies: [
    {
      name: '恒星 α',
      color: '#ffc857',
      massKg: 1.0989e31,
      position: { x: 2.1e8, y: 1.2e6, z: 0 },
      velocity: { x: 1919, y: 1145, z: 0 },
    },
    {
      name: '恒星 β',
      color: '#59c3ff',
      massKg: 1.0289e31,
      position: { x: 2.796e11, y: 2.796e11, z: 0 },
      velocity: { x: 20000, y: -20000, z: 0 },
    },
    {
      name: '恒星 γ',
      color: '#ff647c',
      massKg: 1.0289e31,
      position: { x: -2.796e11, y: -2.796e11, z: 0 },
      velocity: { x: -20000, y: 20000, z: 0 },
    },
  ],
  timeStepSeconds: 43200,
  gravitationalConstant: 6.6743e-11,
  softeningLengthMeters: 1e8,
  maxSteps: 200000,
  targetSimulationTimeSeconds: null,
}

function stripRowIds(draft: ConfigDraft): unknown {
  return {
    ...draft,
    bodies: draft.bodies.map(({ rowId: _rowId, ...rest }) => rest),
  }
}

export const useDraftStore = defineStore('draft', () => {
  const presets = ref<Preset[]>([])
  const presetsLoading = ref(false)
  const presetsError = ref<string | null>(null)
  const selectedPresetKey = ref<PresetKey | null>(null)

  const unitSystem = ref<UnitSystem>('ASTRONOMICAL')
  const draft = ref<ConfigDraft>(configToDraft(FALLBACK_CONFIG, 'ASTRONOMICAL'))
  /** 最近一次已应用的配置，用于判断草稿是否有未提交修改。 */
  const appliedConfig = ref<SimulationConfig | null>(null)
  const serverValidation = ref<ValidationResult | null>(null)
  const validating = ref(false)
  const validationError = ref<string | null>(null)
  const guidanceApplyError = ref<string | null>(null)
  /** 创建请求独立 pending，防止双击重复创建。 */
  const creating = ref(false)
  /** Warning 确认：草稿变化或配置指纹变化后失效。 */
  const warningsConfirmed = ref(false)
  /** 最近一次服务端校验对应的配置指纹。 */
  const validatedFingerprint = ref<string | null>(null)
  const pendingRetryContext = ref<ExperimentRetryContext | null>(null)
  const retrySourceConfig = ref<SimulationConfig | null>(null)
  const retrySourceName = ref<string | null>(null)
  const pendingGuidancePlan = ref<ComparisonExperimentPlan | null>(null)
  let validationGeneration = 0
  let validationTimer: ReturnType<typeof setTimeout> | null = null

  /** 本地即时校验，输入过程中给出反馈。 */
  const localConversion = computed(() => draftToConfig(draft.value, unitSystem.value))

  const localIssues = computed<ValidationIssue[]>(() => localConversion.value.issues)

  /** 本地与服务端问题合并展示，服务端结果优先。 */
  const allIssues = computed<ValidationIssue[]>(() => {
    const server = serverValidation.value?.issues ?? []
    return [...localIssues.value, ...server]
  })

  const errorIssues = computed(() => allIssues.value.filter((item) => item.severity === 'ERROR'))
  const warningIssues = computed(() => allIssues.value.filter((item) => item.severity === 'WARNING'))

  /** 配置指纹：优先用规范化配置，配置不可用（含本地 ERROR）时退化为去 rowId 的草稿。 */
  const configFingerprint = computed(() => {
    const config = localConversion.value.config
    return config ? JSON.stringify(config) : JSON.stringify(stripRowIds(draft.value))
  })

  /** 本地 canSubmit 只由本地 ERROR 与提交中状态决定，旧服务端 ERROR 不锁死按钮。 */
  const canSubmit = computed(
    () => localConversion.value.config !== null && errorIssues.value.length === 0 && !creating.value,
  )

  const isDirty = computed(() => {
    if (!appliedConfig.value) return true
    const current = localConversion.value.config
    if (!current) return true
    return JSON.stringify(current) !== JSON.stringify(appliedConfig.value)
  })

  const retryAttributionLimited = computed(() => {
    if (!retrySourceConfig.value || !pendingRetryContext.value) return false
    const current = localConversion.value.config
    if (!current) return false
    const source = retrySourceConfig.value
    return source.gravitationalConstant !== current.gravitationalConstant
      || source.softeningLengthMeters !== current.softeningLengthMeters
      || source.targetSimulationTimeSeconds !== current.targetSimulationTimeSeconds
      || JSON.stringify(source.bodies) !== JSON.stringify(current.bodies)
  })

  const issuesByField = computed(() => {
    const map = new Map<string, ValidationIssue[]>()
    for (const item of allIssues.value) {
      const existing = map.get(item.field)
      if (existing) existing.push(item)
      else map.set(item.field, [item])
    }
    return map
  })

  /** 任一草稿变化：清空服务端校验结果、校验错误、Warning 确认与已校验指纹。 */
  watch(configFingerprint, () => {
    serverValidation.value = null
    validationError.value = null
    guidanceApplyError.value = null
    pendingGuidancePlan.value = null
    warningsConfirmed.value = false
    validatedFingerprint.value = null
    validationGeneration += 1
    validating.value = false
    if (validationTimer) clearTimeout(validationTimer)
    const config = localConversion.value.config
    if (!config) return
    const generation = validationGeneration
    const fingerprint = configFingerprint.value
    validationTimer = setTimeout(() => {
      validationTimer = null
      void validateSnapshot(config, fingerprint, generation)
    }, 500)
  }, { immediate: true })

  onScopeDispose(() => {
    if (validationTimer) clearTimeout(validationTimer)
  })

  function setUnitSystem(system: UnitSystem): void {
    if (system === unitSystem.value) return
    draft.value = convertDraftUnits(draft.value, unitSystem.value, system)
    unitSystem.value = system
  }

  function loadConfig(config: SimulationConfig, markApplied = false): void {
    selectedPresetKey.value = null
    draft.value = configToDraft(config, unitSystem.value)
    serverValidation.value = null
    validationError.value = null
    warningsConfirmed.value = false
    validatedFingerprint.value = null
    pendingRetryContext.value = null
    retrySourceConfig.value = null
    retrySourceName.value = null
    if (markApplied) {
      appliedConfig.value = JSON.parse(JSON.stringify(config)) as SimulationConfig
    }
  }

  function loadComparisonConfig(config: SimulationConfig, context: ExperimentRetryContext,
    sourceName: string, sourceConfig: SimulationConfig): void {
    selectedPresetKey.value = null
    draft.value = configToDraft(config, unitSystem.value)
    pendingRetryContext.value = context
    retrySourceConfig.value = JSON.parse(JSON.stringify(sourceConfig)) as SimulationConfig
    retrySourceName.value = sourceName
  }

  function applyPreset(key: string): void {
    const preset = presets.value.find((item) => item.key === key)
    if (!preset) return
    loadConfig(preset.config)
    selectedPresetKey.value = preset.key
  }

  function addBody(): void {
    if (draft.value.bodies.length >= MAX_BODY_COUNT) return
    draft.value.bodies.push(createBodyDraft(draft.value.bodies.length))
  }

  function duplicateBody(rowId: string): void {
    if (draft.value.bodies.length >= MAX_BODY_COUNT) return
    const index = draft.value.bodies.findIndex((item) => item.rowId === rowId)
    if (index < 0) return
    draft.value.bodies.splice(index + 1, 0, duplicateBodyDraft(draft.value.bodies[index]))
  }

  function removeBody(rowId: string): void {
    if (draft.value.bodies.length <= MIN_BODY_COUNT) return
    draft.value.bodies = draft.value.bodies.filter((item) => item.rowId !== rowId)
  }

  function moveBody(rowId: string, direction: -1 | 1): void {
    const index = draft.value.bodies.findIndex((item) => item.rowId === rowId)
    const target = index + direction
    if (index < 0 || target < 0 || target >= draft.value.bodies.length) return
    const bodies = [...draft.value.bodies]
    const [moved] = bodies.splice(index, 1)
    bodies.splice(target, 0, moved)
    draft.value.bodies = bodies
  }

  async function loadPresets(): Promise<void> {
    const fingerprintBeforeLoad = configFingerprint.value
    presetsLoading.value = true
    presetsError.value = null
    try {
      presets.value = await api.listPresets()
      if (selectedPresetKey.value === null && configFingerprint.value === fingerprintBeforeLoad) {
        applyPreset('A')
      }
    } catch (error) {
      presetsError.value = error instanceof ApiError ? error.message : '加载预设失败。'
    } finally {
      presetsLoading.value = false
    }
  }

  /**
   * 提交前调用服务端校验，返回完整结果。
   * 返回 null 表示本地配置不可用或校验请求失败（validationError 已记录）。
   */
  async function validateSnapshot(config: SimulationConfig, fingerprint: string,
    generation: number): Promise<ValidationResult | null> {
    validating.value = true
    validationError.value = null
    try {
      const result = await api.validateConfig(config)
      if (generation !== validationGeneration || fingerprint !== configFingerprint.value) return null
      serverValidation.value = result
      validatedFingerprint.value = fingerprint
      return result
    } catch (error) {
      if (generation !== validationGeneration || fingerprint !== configFingerprint.value) return null
      validationError.value = error instanceof ApiError ? error.message : '校验请求失败。'
      serverValidation.value = null
      return null
    } finally {
      if (generation === validationGeneration) validating.value = false
    }
  }

  async function validateWithServer(): Promise<ValidationResult | null> {
    const config = localConversion.value.config
    if (!config) return null
    if (validationTimer) {
      clearTimeout(validationTimer)
      validationTimer = null
    }
    validationGeneration += 1
    return validateSnapshot(config, configFingerprint.value, validationGeneration)
  }

  function applyGuidanceAction(action: GuidanceAction): boolean {
    const current = localConversion.value.config
    const nextDt = action.configPatch?.timeStepSeconds
    if (!current || action.mode !== 'APPLY_PATCH' || nextDt == null) return false
    const plan = planComparisonExperiment(current, nextDt)
    if (plan.exceedsStepLimit) {
      guidanceApplyError.value = '保持当前模拟时长会超过 100,000,000 步。请缩短模拟时长，或手动选择更大的时间步长。'
      pendingGuidancePlan.value = plan
      return false
    }
    const retryContext = pendingRetryContext.value
    const sourceConfig = retrySourceConfig.value
    const sourceName = retrySourceName.value
    draft.value = configToDraft({ ...plan.proposedConfig, name: current.name }, unitSystem.value)
    pendingRetryContext.value = retryContext
    retrySourceConfig.value = sourceConfig
    retrySourceName.value = sourceName
    return true
  }

  function resolveGuidanceStepLimit(resolution: StepLimitResolution): void {
    const plan = pendingGuidancePlan.value
    if (!plan) return
    const resolved = resolveStepLimit(plan, resolution)
    const retryContext = pendingRetryContext.value
    const sourceConfig = retrySourceConfig.value
    const sourceName = retrySourceName.value
    draft.value = configToDraft(resolved.proposedConfig, unitSystem.value)
    pendingRetryContext.value = retryContext
    retrySourceConfig.value = sourceConfig
    retrySourceName.value = sourceName
    pendingGuidancePlan.value = null
    guidanceApplyError.value = null
  }

  function cancelGuidanceStepLimit(): void {
    pendingGuidancePlan.value = null
    guidanceApplyError.value = null
  }

  function markApplied(config: SimulationConfig): void {
    appliedConfig.value = JSON.parse(JSON.stringify(config)) as SimulationConfig
  }

  function clearRetryContext(): void {
    pendingRetryContext.value = null
    retrySourceConfig.value = null
    retrySourceName.value = null
  }

  function resetToApplied(): void {
    if (appliedConfig.value) {
      loadConfig(appliedConfig.value)
    }
  }

  function confirmWarnings(): void {
    warningsConfirmed.value = true
  }

  function dismissWarnings(): void {
    warningsConfirmed.value = false
  }

  function beginCreate(): void {
    creating.value = true
  }

  function endCreate(): void {
    creating.value = false
  }

  /** 导入 JSON：接受完整配置文档或 { config: ... } 包装。 */
  function importFromJson(text: string): { ok: true } | { ok: false; message: string } {
    let parsed: unknown
    try {
      parsed = JSON.parse(text)
    } catch {
      return { ok: false, message: '文件不是合法 JSON。' }
    }
    const candidate =
      typeof parsed === 'object' && parsed !== null && 'config' in parsed
        ? (parsed as { config: unknown }).config
        : parsed
    if (typeof candidate !== 'object' || candidate === null) {
      return { ok: false, message: 'JSON 顶层必须是配置对象。' }
    }
    const config = candidate as Partial<SimulationConfig>
    if (!Array.isArray(config.bodies) || config.bodies.length < MIN_BODY_COUNT) {
      return { ok: false, message: `配置至少需要 ${MIN_BODY_COUNT} 个天体。` }
    }
    if (config.bodies.length > MAX_BODY_COUNT) {
      return { ok: false, message: `配置最多支持 ${MAX_BODY_COUNT} 个天体。` }
    }
    if (typeof config.timeStepSeconds !== 'number' || typeof config.gravitationalConstant !== 'number') {
      return { ok: false, message: '配置缺少时间步长或引力常数。' }
    }
    loadConfig(config as SimulationConfig)
    return { ok: true }
  }

  function exportToJson(): string {
    const config = localConversion.value.config
    return JSON.stringify(
      {
        schemaVersion: '1.0',
        exportedAt: new Date().toISOString(),
        unitSystem: 'SI',
        config: config ?? null,
      },
      null,
      2,
    )
  }

  return {
    presets,
    presetsLoading,
    presetsError,
    selectedPresetKey,
    unitSystem,
    draft,
    appliedConfig,
    serverValidation,
    validating,
    validationError,
    guidanceApplyError,
    creating,
    warningsConfirmed,
    validatedFingerprint,
    pendingRetryContext,
    retrySourceConfig,
    retrySourceName,
    retryAttributionLimited,
    pendingGuidancePlan,
    configFingerprint,
    localIssues,
    allIssues,
    errorIssues,
    warningIssues,
    issuesByField,
    canSubmit,
    isDirty,
    localConversion,
    setUnitSystem,
    loadConfig,
    loadComparisonConfig,
    applyPreset,
    addBody,
    duplicateBody,
    removeBody,
    moveBody,
    loadPresets,
    validateWithServer,
    applyGuidanceAction,
    resolveGuidanceStepLimit,
    cancelGuidanceStepLimit,
    markApplied,
    clearRetryContext,
    resetToApplied,
    confirmWarnings,
    dismissWarnings,
    beginCreate,
    endCreate,
    importFromJson,
    exportToJson,
  }
})
