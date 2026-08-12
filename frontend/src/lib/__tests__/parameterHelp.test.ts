import { describe, expect, it } from 'vitest'
import { getParameterHelp, type ParameterKind } from '../parameterHelp'

const ALL_KINDS: ParameterKind[] = [
  'mass',
  'position',
  'velocity',
  'timeStep',
  'softeningLength',
  'totalTime',
  'maxSteps',
  'gravitationalConstant',
]

describe('getParameterHelp', () => {
  it('覆盖全部参数种类且文案非空', () => {
    for (const kind of ALL_KINDS) {
      const help = getParameterHelp(kind, 'SI')
      expect(help.meaning.length).toBeGreaterThan(0)
      expect(help.impact.length).toBeGreaterThan(0)
      expect(help.range.length).toBeGreaterThan(0)
    }
  })

  it('质量示例随单位制切换', () => {
    const si = getParameterHelp('mass', 'SI')
    const astro = getParameterHelp('mass', 'ASTRONOMICAL')
    expect(si.example).toContain('kg')
    expect(astro.example).toContain('M☉')
  })

  it('长度与速度类帮助使用对应动态单位', () => {
    const positionAstro = getParameterHelp('position', 'ASTRONOMICAL')
    expect(positionAstro.range).toContain('AU')
    const positionSi = getParameterHelp('position', 'SI')
    expect(positionSi.range).toContain('m')
    const velocityAstro = getParameterHelp('velocity', 'ASTRONOMICAL')
    expect(velocityAstro.range).toContain('km/s')
  })

  it('软化长度包含参考区间说明', () => {
    const help = getParameterHelp('softeningLength', 'SI')
    expect(help.range).toContain('1e-4～1e-2')
  })
})
