import { describe, expect, it } from 'vitest'
import {
  ASTRONOMICAL_UNIT_M,
  JULIAN_YEAR_S,
  SOLAR_MASS_KG,
  auToMeters,
  daysToSeconds,
  fromSi,
  kmpsToMps,
  kgToSolarMass,
  metersToAu,
  mpsToKmps,
  secondsToDays,
  secondsToYears,
  solarMassToKg,
  toSi,
  yearsToSeconds,
} from '../units'

describe('units 双向换算', () => {
  it('质量：kg <-> 太阳质量', () => {
    expect(solarMassToKg(1)).toBeCloseTo(SOLAR_MASS_KG)
    expect(kgToSolarMass(SOLAR_MASS_KG)).toBeCloseTo(1)
    expect(kgToSolarMass(0)).toBe(0)
  })

  it('长度：m <-> AU', () => {
    expect(auToMeters(1)).toBeCloseTo(ASTRONOMICAL_UNIT_M)
    expect(metersToAu(ASTRONOMICAL_UNIT_M)).toBeCloseTo(1)
  })

  it('速度：m/s <-> km/s', () => {
    expect(kmpsToMps(1)).toBe(1000)
    expect(mpsToKmps(1000)).toBe(1)
  })

  it('时间：s <-> 年 / 天', () => {
    expect(yearsToSeconds(1)).toBeCloseTo(JULIAN_YEAR_S)
    expect(secondsToYears(JULIAN_YEAR_S)).toBeCloseTo(1)
    expect(daysToSeconds(1)).toBe(86400)
    expect(secondsToDays(86400)).toBe(1)
  })

  it('通用 fromSi / toSi 与单位制一致', () => {
    expect(fromSi(1.5, 'mass', 'ASTRONOMICAL')).toBeCloseTo(1.5 / SOLAR_MASS_KG)
    expect(toSi(fromSi(1.5, 'mass', 'ASTRONOMICAL'), 'mass', 'ASTRONOMICAL')).toBeCloseTo(1.5)
    expect(fromSi(2, 'length', 'SI')).toBe(2)
    expect(fromSi(2, 'velocity', 'ASTRONOMICAL')).toBe(0.002)
    expect(toSi(0.002, 'velocity', 'ASTRONOMICAL')).toBe(2)
  })

  it('SI 单位制下是恒等变换', () => {
    expect(toSi(123.4, 'mass', 'SI')).toBe(123.4)
    expect(toSi(123.4, 'time', 'SI')).toBe(123.4)
  })
})
