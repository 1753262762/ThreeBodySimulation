import { describe, expect, it } from 'vitest'
import {
  formatBytes,
  formatInteger,
  formatPercent,
  formatScientific,
  formatSimulationTime,
  isFiniteNumber,
  parseNumberInput,
} from '../format'

describe('format 工具', () => {
  it('formatScientific 对非法输入返回占位符', () => {
    expect(formatScientific(Number.NaN)).toBe('—')
    expect(formatScientific(Number.POSITIVE_INFINITY)).toBe('—')
    expect(formatScientific(null)).toBe('—')
    expect(formatScientific(undefined)).toBe('—')
  })

  it('formatScientific 大数用科学计数法，常规数用定点', () => {
    expect(formatScientific(0)).toBe('0')
    expect(formatScientific(3.8419e41)).toMatch(/e41$/)
    expect(formatScientific(43200)).toBe('43200')
    expect(formatScientific(1.5)).toBe('1.5')
  })

  it('formatPercent 相对漂移带符号', () => {
    expect(formatPercent(0.00044)).toBe('+0.044%')
    expect(formatPercent(-2.1e-5)).toContain('%')
    expect(formatPercent(Number.NaN)).toBe('—')
  })

  it('formatInteger 千分位', () => {
    expect(formatInteger(18462)).toBe('18,462')
    expect(formatInteger(null)).toBe('—')
  })

  it('formatSimulationTime 自动进位', () => {
    expect(formatSimulationTime(90)).toContain('s')
    expect(formatSimulationTime(797558400)).toContain('年')
    expect(formatSimulationTime(90000)).toContain('h')
  })

  it('formatBytes 可读', () => {
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(18874368)).toContain('MB')
    expect(formatBytes(null)).toBe('—')
  })

  it('parseNumberInput 支持科学计数法与千分位', () => {
    expect(parseNumberInput('1.5e11')).toBe(1.5e11)
    expect(parseNumberInput('1,500')).toBe(1500)
    expect(parseNumberInput(2000000)).toBe(2000000)
    expect(parseNumberInput('')).toBeNull()
    expect(parseNumberInput('abc')).toBeNull()
  })

  it('isFiniteNumber', () => {
    expect(isFiniteNumber(1)).toBe(true)
    expect(isFiniteNumber(Number.NaN)).toBe(false)
    expect(isFiniteNumber('1')).toBe(false)
  })
})
