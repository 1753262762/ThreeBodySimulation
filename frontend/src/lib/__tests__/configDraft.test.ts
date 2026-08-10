import { describe, expect, it } from 'vitest'
import {
  configToDraft,
  createBodyDraft,
  draftToConfig,
  duplicateBodyDraft,
  resetRowIdCounter,
} from '../configDraft'
import type { SimulationConfig } from '../../contracts'

function baseConfig(): SimulationConfig {
  return {
    name: '测试实验',
    bodies: [
      { id: 'a', name: 'α', color: '#ffc857', massKg: 1e30, position: { x: 0, y: 0, z: 0 }, velocity: { x: 0, y: 0, z: 0 } },
      { id: 'b', name: 'β', color: '#59c3ff', massKg: 2e30, position: { x: 1e11, y: 0, z: 0 }, velocity: { x: 1000, y: 0, z: 0 } },
    ],
    timeStepSeconds: 43200,
    gravitationalConstant: 6.6743e-11,
    softeningLengthMeters: 1e8,
    maxSteps: 200000,
    targetSimulationTimeSeconds: null,
  }
}

describe('configDraft 草稿转换', () => {
  it('configToDraft 往返保持 SI 数值（天文单位制）', () => {
    resetRowIdCounter()
    const config = baseConfig()
    const draft = configToDraft(config, 'ASTRONOMICAL')
    const back = draftToConfig(draft, 'ASTRONOMICAL')
    expect(back.config).not.toBeNull()
    expect(back.issues).toEqual([])
    expect(back.config!.bodies[1].massKg).toBeCloseTo(2e30, -20)
    expect(back.config!.bodies[1].position.x).toBeCloseTo(1e11, -20)
    expect(back.config!.bodies[1].velocity.x).toBeCloseTo(1000, -18)
  })

  it('非正质量产生 INVALID_MASS 错误且 config 为 null', () => {
    resetRowIdCounter()
    const draft = configToDraft(baseConfig(), 'SI')
    draft.bodies[0].mass = '-5'
    const result = draftToConfig(draft, 'SI')
    expect(result.config).toBeNull()
    expect(result.issues.some((i) => i.code === 'INVALID_MASS')).toBe(true)
  })

  it('缺少结束条件产生 MISSING_END_CONDITION', () => {
    resetRowIdCounter()
    const draft = configToDraft(baseConfig(), 'SI')
    draft.maxSteps = ''
    draft.endCondition = 'MAX_STEPS'
    const result = draftToConfig(draft, 'SI')
    expect(result.config).toBeNull()
    expect(result.issues.some((i) => i.code === 'MISSING_END_CONDITION')).toBe(true)
  })

  it('完全重合的天体产生 COINCIDENT_BODIES', () => {
    resetRowIdCounter()
    const draft = configToDraft(baseConfig(), 'SI')
    draft.bodies[1].positionX = '0'
    draft.bodies[1].positionY = '0'
    draft.bodies[1].positionZ = '0'
    const result = draftToConfig(draft, 'SI')
    expect(result.issues.some((i) => i.code === 'COINCIDENT_BODIES')).toBe(true)
  })

  it('createBodyDraft 与 duplicateBodyDraft 生成独立 rowId', () => {
    resetRowIdCounter()
    const a = createBodyDraft(0)
    const b = duplicateBodyDraft(a)
    expect(a.rowId).not.toBe(b.rowId)
    expect(b.id).toBeNull()
    expect(b.name).toContain('副本')
  })

  it('BOTH 结束条件同时写入 maxSteps 与目标时间', () => {
    resetRowIdCounter()
    const draft = configToDraft(baseConfig(), 'SI')
    draft.endCondition = 'BOTH'
    draft.targetSimulationTime = '31557600'
    const result = draftToConfig(draft, 'SI')
    expect(result.config).not.toBeNull()
    expect(result.config!.maxSteps).toBe(200000)
    expect(result.config!.targetSimulationTimeSeconds).toBeCloseTo(31557600)
  })
})
