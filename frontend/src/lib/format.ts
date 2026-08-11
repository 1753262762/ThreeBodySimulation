/**
 * 数值与时间格式化。
 *
 * 物理量跨越几十个数量级，统一用有限有效数字的科学计数法展示，
 * 避免出现 3.8419999999999998e41 之类的噪声。
 */

/** 非有限值统一展示为占位符，防止界面出现 NaN 或 Infinity。 */
const NON_FINITE_PLACEHOLDER = '—'

export function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

/**
 * 科学计数法，形如 -3.842e41。
 * 数量级在 1e-3 与 1e6 之间时改用定点表示，读起来更自然。
 */
export function formatScientific(value: number | null | undefined, digits = 4): string {
  if (!isFiniteNumber(value)) return NON_FINITE_PLACEHOLDER
  if (value === 0) return '0'
  const magnitude = Math.abs(value)
  if (magnitude >= 1e-3 && magnitude < 1e6) {
    return trimTrailingZeros(value.toPrecision(digits + 1))
  }
  const exponent = Math.floor(Math.log10(magnitude))
  const mantissa = value / 10 ** exponent
  return `${trimTrailingZeros(mantissa.toFixed(digits))}e${exponent}`
}

function trimTrailingZeros(text: string): string {
  if (!text.includes('.')) return text
  return text.replace(/\.?0+$/, '')
}

/** 固定小数位，用于比例、漂移等有界数值。 */
export function formatFixed(value: number | null | undefined, digits = 3): string {
  if (!isFiniteNumber(value)) return NON_FINITE_PLACEHOLDER
  return value.toFixed(digits)
}

/** 相对漂移按百分比展示，带符号便于判断能量增减方向。 */
export function formatPercent(value: number | null | undefined, digits = 4): string {
  if (!isFiniteNumber(value)) return NON_FINITE_PLACEHOLDER
  const percent = value * 100
  const sign = percent > 0 ? '+' : ''
  if (percent !== 0 && Math.abs(percent) < 10 ** -digits) {
    return `${sign}${percent.toExponential(2)}%`
  }
  const fixed = percent.toFixed(digits).replace(/\.?0+$/, '')
  return `${sign}${fixed}%`
}

export function formatInteger(value: number | null | undefined): string {
  if (!isFiniteNumber(value)) return NON_FINITE_PLACEHOLDER
  return Math.round(value).toLocaleString('zh-CN')
}

/** 模拟时间：秒数较大时自动进位到天或年，并保留原始单位提示。 */
export function formatSimulationTime(seconds: number | null | undefined): string {
  if (!isFiniteNumber(seconds)) return NON_FINITE_PLACEHOLDER
  const magnitude = Math.abs(seconds)
  if (magnitude < 120) return `${formatFixed(seconds, 1)} s`
  if (magnitude < 172800) return `${formatFixed(seconds / 3600, 2)} h`
  if (magnitude < 31557600 * 2) return `${formatFixed(seconds / 86400, 2)} d`
  return `${formatFixed(seconds / 31557600, 3)} 年`
}

/** 墙钟耗时，用于报告页。 */
export function formatWallClock(seconds: number | null | undefined): string {
  if (!isFiniteNumber(seconds)) return NON_FINITE_PLACEHOLDER
  const total = Math.max(0, Math.round(seconds))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const secs = total % 60
  const parts: string[] = []
  if (hours > 0) parts.push(`${hours} 小时`)
  if (hours > 0 || minutes > 0) parts.push(`${minutes} 分`)
  parts.push(`${secs} 秒`)
  return parts.join(' ')
}

export function formatBytes(bytes: number | null | undefined): string {
  if (!isFiniteNumber(bytes)) return NON_FINITE_PLACEHOLDER
  if (bytes < 1024) return `${Math.round(bytes)} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes / 1024
  let index = 0
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024
    index += 1
  }
  return `${value.toFixed(value >= 100 ? 0 : 1)} ${units[index]}`
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return NON_FINITE_PLACEHOLDER
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return NON_FINITE_PLACEHOLDER
  return date.toLocaleString('zh-CN', { hour12: false })
}

/** 解析用户输入，接受 1.5e11、1,500 等写法；非法输入返回 null 由校验层处理。 */
export function parseNumberInput(raw: string | number): number | null {
  const trimmed = String(raw).trim().replace(/,/g, '')
  if (trimmed === '') return null
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? parsed : null
}
