/**
 * 单位换算。
 *
 * 口径约定：Pinia 状态与所有 API 交互一律使用 SI（kg、m、m/s、s）。
 * 只有展示层与表单输入层才会出现天文单位（太阳质量、AU、km/s、年）。
 * 因此换算函数必须成对且可逆，避免草稿往返产生漂移。
 */

/** 太阳质量，单位 kg。IAU 2015 名义值。 */
export const SOLAR_MASS_KG = 1.98892e30

/** 天文单位，单位 m。IAU 2012 定义值。 */
export const ASTRONOMICAL_UNIT_M = 1.495978707e11

/** 儒略年，单位 s。365.25 天。 */
export const JULIAN_YEAR_S = 31557600

/** 儒略日，单位 s。 */
export const DAY_S = 86400

export type UnitSystem = 'SI' | 'ASTRONOMICAL'

export const UNIT_SYSTEM_LABELS: Record<UnitSystem, string> = {
  SI: 'SI 单位',
  ASTRONOMICAL: '天文单位',
}

export interface UnitLabels {
  mass: string
  length: string
  velocity: string
  time: string
}

export const UNIT_LABELS: Record<UnitSystem, UnitLabels> = {
  SI: { mass: 'kg', length: 'm', velocity: 'm/s', time: 's' },
  ASTRONOMICAL: { mass: 'M☉', length: 'AU', velocity: 'km/s', time: '年' },
}

export function kgToSolarMass(kg: number): number {
  return kg / SOLAR_MASS_KG
}

export function solarMassToKg(solarMass: number): number {
  return solarMass * SOLAR_MASS_KG
}

export function metersToAu(meters: number): number {
  return meters / ASTRONOMICAL_UNIT_M
}

export function auToMeters(au: number): number {
  return au * ASTRONOMICAL_UNIT_M
}

export function mpsToKmps(mps: number): number {
  return mps / 1000
}

export function kmpsToMps(kmps: number): number {
  return kmps * 1000
}

export function secondsToYears(seconds: number): number {
  return seconds / JULIAN_YEAR_S
}

export function yearsToSeconds(years: number): number {
  return years * JULIAN_YEAR_S
}

export function secondsToDays(seconds: number): number {
  return seconds / DAY_S
}

export function daysToSeconds(days: number): number {
  return days * DAY_S
}

/** 把 SI 数值转换为指定单位制下的展示数值。 */
export function fromSiMass(kg: number, system: UnitSystem): number {
  return system === 'SI' ? kg : kgToSolarMass(kg)
}

export function toSiMass(value: number, system: UnitSystem): number {
  return system === 'SI' ? value : solarMassToKg(value)
}

export function fromSiLength(meters: number, system: UnitSystem): number {
  return system === 'SI' ? meters : metersToAu(meters)
}

export function toSiLength(value: number, system: UnitSystem): number {
  return system === 'SI' ? value : auToMeters(value)
}

export function fromSiVelocity(mps: number, system: UnitSystem): number {
  return system === 'SI' ? mps : mpsToKmps(mps)
}

export function toSiVelocity(value: number, system: UnitSystem): number {
  return system === 'SI' ? value : kmpsToMps(value)
}

export function fromSiTime(seconds: number, system: UnitSystem): number {
  return system === 'SI' ? seconds : secondsToYears(seconds)
}

export function toSiTime(value: number, system: UnitSystem): number {
  return system === 'SI' ? value : yearsToSeconds(value)
}

export type QuantityKind = 'mass' | 'length' | 'velocity' | 'time'

export function fromSi(value: number, kind: QuantityKind, system: UnitSystem): number {
  switch (kind) {
    case 'mass':
      return fromSiMass(value, system)
    case 'length':
      return fromSiLength(value, system)
    case 'velocity':
      return fromSiVelocity(value, system)
    case 'time':
      return fromSiTime(value, system)
  }
}

export function toSi(value: number, kind: QuantityKind, system: UnitSystem): number {
  switch (kind) {
    case 'mass':
      return toSiMass(value, system)
    case 'length':
      return toSiLength(value, system)
    case 'velocity':
      return toSiVelocity(value, system)
    case 'time':
      return toSiTime(value, system)
  }
}

export function unitLabel(kind: QuantityKind, system: UnitSystem): string {
  return UNIT_LABELS[system][kind]
}