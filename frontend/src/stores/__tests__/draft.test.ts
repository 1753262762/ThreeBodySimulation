import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import type { ValidationResult } from '../../contracts'
import { api } from '../../lib/apiClient'
import { useDraftStore } from '../draft'

function warningResult(): ValidationResult {
  return {
    valid: true,
    issues: [
      {
        field: 'timeStepSeconds',
        code: 'TIME_STEP_TOO_LARGE',
        message: '时间步长相对轨道周期偏大。',
        severity: 'WARNING',
        riskLevel: 'CAUTION',
      },
    ],
    normalizedConfig: null,
    estimatedSteps: 2000,
  }
}

describe('draft store（F2/F3 提交流）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('默认配置 canSubmit 为 true，本地错误会锁死按钮', () => {
    const store = useDraftStore()
    expect(store.canSubmit).toBe(true)
    store.draft.bodies[0].mass = '0'
    expect(store.localConversion.config).toBeNull()
    expect(store.canSubmit).toBe(false)
  })

  it('validateWithServer 记录结果与指纹，草稿变化后全部失效', async () => {
    const spy = vi.spyOn(api, 'validateConfig').mockResolvedValue(warningResult())
    const store = useDraftStore()
    const result = await store.validateWithServer()
    expect(result?.issues[0].riskLevel).toBe('CAUTION')
    expect(store.serverValidation).not.toBeNull()
    expect(store.validatedFingerprint).not.toBeNull()
    expect(store.warningsConfirmed).toBe(false)

    store.draft.timeStep = '0.002'
    await nextTick()
    expect(store.serverValidation).toBeNull()
    expect(store.validatedFingerprint).toBeNull()
    expect(store.warningsConfirmed).toBe(false)
    spy.mockRestore()
  })

  it('Warning 确认随草稿变化失效，返回修改会清除确认', async () => {
    vi.spyOn(api, 'validateConfig').mockResolvedValue(warningResult())
    const store = useDraftStore()
    await store.validateWithServer()
    expect(store.warningIssues.length).toBe(1)

    store.confirmWarnings()
    expect(store.warningsConfirmed).toBe(true)

    store.draft.maxSteps = '100'
    await nextTick()
    expect(store.warningsConfirmed).toBe(false)

    store.confirmWarnings()
    store.dismissWarnings()
    expect(store.warningsConfirmed).toBe(false)
  })

  it('beginCreate/endCreate 防止重复创建', () => {
    const store = useDraftStore()
    expect(store.creating).toBe(false)
    store.beginCreate()
    expect(store.creating).toBe(true)
    expect(store.canSubmit).toBe(false)
    store.endCreate()
    expect(store.creating).toBe(false)
    expect(store.canSubmit).toBe(true)
  })

  it('markApplied 后未修改配置 isDirty 为 false，修改后为 true', () => {
    const store = useDraftStore()
    const config = store.localConversion.config
    expect(config).not.toBeNull()
    store.markApplied(config!)
    expect(store.isDirty).toBe(false)
    store.draft.name = '改名'
    expect(store.isDirty).toBe(true)
  })
})