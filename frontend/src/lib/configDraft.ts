/**
 * 参数草稿模型。
 *
 * 草稿字段全部是字符串，因为输入框需要保留用户正在键入的中间状态
 * （例如 "1.5e" 或 "-"），只有提交前才转换为 SI 数值。
 * 草稿显示值使用当前单位制，转换回 SI 由 toConfig 负责。
 */
import {
  MAX_BODY_COUNT,
  MIN_BODY_COUNT,
  type BodySpec,
  type SimulationConfig,
  type ValidationIssue,
} from '../contracts'
import { parseNumberInput } from './format'
import { fromSi, toSi, type UnitSystem } from './units'

export interface BodyDraft {
  /** 本地行标识，仅用于列表 key，与后端 id 无关。 */
  rowId: string
  /** 已有天体的服务端 id；新增天体为 null，由服务端补齐。 */
  id: string | null
  name: string
  color: string
  mass: string
  positionX: string
  positionY: string
  positionZ: string
  velocityX: string
  velocityY: string
  velocityZ: string
}

export type EndConditionMode = 'MAX_STEPS' | 'TARGET_TIME' | 'BOTH'

export interface ConfigDraft {
  name: string
  bodies: BodyDraft[]
  timeStep: string
  gravitationalConstant: string
  softeningLength: string
  endCondition: EndConditionMode
  maxSteps: string
  targetSimulationTime: string
}

export const DEFAULT_BODY_COLORS = [
  '#ffc857',
  '#59c3ff',
  '#ff647c',
  '#c084fc',
  '#4ade80',
  '#fb923c',
  '#38bdf8',
  '#f472b6',
  '#a3e635',
  '#facc15',
]

let rowCounter = 0

function nextRowId(): string {
  rowCounter += 1
  return `row-${rowCounter}`
}

/** 供测试重置自增计数，保证快照稳定。 */
export function resetRowIdCounter(): void {
  rowCounter = 0
}

function numberToDraft(value: number): string {
  if (!Number.isFinite(value)) return ''
  if (value === 0) return '0'
  const magnitude = Math.abs(value)
  if (magnitude >= 1e-4 && magnitude < 1e7) {
    return String(Number(value.toPrecision(12)))
  }
  return value.toExponential(6).replace(/e\+?/, 'e')
}

export function bodyToDraft(body: BodySpec, system: UnitSystem, index: number): BodyDraft {
  return {
    rowId: nextRowId(),
    id: body.id ?? null,
    name: body.name,
    color: body.color ?? DEFAULT_BODY_COLORS[index % DEFAULT_BODY_COLORS.length],
    mass: numberToDraft(fromSi(body.massKg, 'mass', system)),
    positionX: numberToDraft(fromSi(body.position.x, 'length', system)),
    positionY: numberToDraft(fromSi(body.position.y, 'length', system)),
    positionZ: numberToDraft(fromSi(body.position.z, 'length', system)),
    velocityX: numberToDraft(fromSi(body.velocity.x, 'velocity', system)),
    velocityY: numberToDraft(fromSi(body.velocity.y, 'velocity', system)),
    velocityZ: numberToDraft(fromSi(body.velocity.z, 'velocity', system)),
  }
}

export function configToDraft(config: SimulationConfig, system: UnitSystem): ConfigDraft {
  const hasMaxSteps = config.maxSteps !== null && config.maxSteps !== undefined
  const hasTargetTime =
    config.targetSimulationTimeSeconds !== null && config.targetSimulationTimeSeconds !== undefined
  return {
    name: config.name ?? '',
    bodies: config.bodies.map((body, index) => bodyToDraft(body, system, index)),
    timeStep: numberToDraft(fromSi(config.timeStepSeconds, 'time', system)),
    gravitationalConstant: numberToDraft(config.gravitationalConstant),
    softeningLength: numberToDraft(fromSi(config.softeningLengthMeters, 'length', system)),
    endCondition: hasMaxSteps && hasTargetTime ? 'BOTH' : hasTargetTime ? 'TARGET_TIME' : 'MAX_STEPS',
    maxSteps: hasMaxSteps ? String(config.maxSteps) : '',
    targetSimulationTime: hasTargetTime
      ? numberToDraft(fromSi(config.targetSimulationTimeSeconds as number, 'time', system))
      : '',
  }
}

/**
 * 切换单位制时重写草稿里的数值，使显示值与新单位一致。
 * 无法解析的中间输入原样保留，避免用户输入被清空。
 */
export function convertDraftUnits(
  draft: ConfigDraft,
  from: UnitSystem,
  to: UnitSystem,
): ConfigDraft {
  if (from === to) return draft
  const convert = (raw: string, kind: 'mass' | 'length' | 'velocity' | 'time'): string => {
    const parsed = parseNumberInput(raw)
    if (parsed === null) return raw
    return numberToDraft(fromSi(toSi(parsed, kind, from), kind, to))
  }
  return {
    ...draft,
    timeStep: convert(draft.timeStep, 'time'),
    softeningLength: convert(draft.softeningLength, 'length'),
    targetSimulationTime: convert(draft.targetSimulationTime, 'time'),
    bodies: draft.bodies.map((body) => ({
      ...body,
      mass: convert(body.mass, 'mass'),
      positionX: convert(body.positionX, 'length'),
      positionY: convert(body.positionY, 'length'),
      positionZ: convert(body.positionZ, 'length'),
      velocityX: convert(body.velocityX, 'velocity'),
      velocityY: convert(body.velocityY, 'velocity'),
      velocityZ: convert(body.velocityZ, 'velocity'),
    })),
  }
}

export function createBodyDraft(index: number): BodyDraft {
  return {
    rowId: nextRowId(),
    id: null,
    name: `天体 ${index + 1}`,
    color: DEFAULT_BODY_COLORS[index % DEFAULT_BODY_COLORS.length],
    mass: '',
    positionX: '0',
    positionY: '0',
    positionZ: '0',
    velocityX: '0',
    velocityY: '0',
    velocityZ: '0',
  }
}

export function duplicateBodyDraft(source: BodyDraft): BodyDraft {
  return {
    ...source,
    rowId: nextRowId(),
    // 复制的天体必须由服务端重新分配 id，否则会触发 DUPLICATE_BODY_ID。
    id: null,
    name: `${source.name} 副本`,
  }
}

export interface DraftConversion {
  config: SimulationConfig | null
  issues: ValidationIssue[]
}

function issue(field: string, code: ValidationIssue['code'], message: string): ValidationIssue {
  return { field, code, message, severity: 'ERROR' }
}

/**
 * 把草稿转换为 SI 配置，并做与契约一致的本地前置校验。
 * 本地校验只用于即时反馈，服务端 /configs/validate 仍是唯一权威。
 */
export function draftToConfig(draft: ConfigDraft, system: UnitSystem): DraftConversion {
  const issues: ValidationIssue[] = []

  if (draft.bodies.length < MIN_BODY_COUNT || draft.bodies.length > MAX_BODY_COUNT) {
    issues.push(
      issue(
        'bodies',
        'BODY_COUNT_OUT_OF_RANGE',
        `天体数量必须介于 ${MIN_BODY_COUNT} 与 ${MAX_BODY_COUNT} 之间，当前为 ${draft.bodies.length}。`,
      ),
    )
  }

  const seenIds = new Set<string>()
  const bodies: BodySpec[] = []

  draft.bodies.forEach((body, index) => {
    const prefix = `bodies[${index}]`
    if (body.name.trim() === '') {
      issues.push(issue(`${prefix}.name`, 'MISSING_BODY_NAME', '天体名称不能为空。'))
    }
    if (!/^#[0-9a-fA-F]{6}$/.test(body.color)) {
      issues.push(issue(`${prefix}.color`, 'INVALID_COLOR', '颜色必须是 #RRGGBB 形式。'))
    }
    if (body.id) {
      if (seenIds.has(body.id)) {
        issues.push(issue(`${prefix}.id`, 'DUPLICATE_BODY_ID', '天体 ID 重复。'))
      }
      seenIds.add(body.id)
    }

    const mass = parseNumberInput(body.mass)
    if (mass === null) {
      issues.push(issue(`${prefix}.massKg`, 'INVALID_MASS', '质量必须是有限数。'))
    } else if (mass <= 0) {
      issues.push(issue(`${prefix}.massKg`, 'INVALID_MASS', '质量必须为正数。'))
    }

    const coords = [
      ['position.x', body.positionX],
      ['position.y', body.positionY],
      ['position.z', body.positionZ],
      ['velocity.x', body.velocityX],
      ['velocity.y', body.velocityY],
      ['velocity.z', body.velocityZ],
    ] as const
    const parsedCoords: number[] = []
    for (const [field, raw] of coords) {
      const parsed = parseNumberInput(raw)
      if (parsed === null) {
        issues.push(issue(`${prefix}.${field}`, 'NON_FINITE_VALUE', '必须是有限数。'))
        parsedCoords.push(Number.NaN)
      } else {
        parsedCoords.push(parsed)
      }
    }

    if (mass !== null && mass > 0 && parsedCoords.every((value) => Number.isFinite(value))) {
      bodies.push({
        id: body.id ?? undefined,
        name: body.name.trim(),
        color: body.color,
        massKg: toSi(mass, 'mass', system),
        position: {
          x: toSi(parsedCoords[0], 'length', system),
          y: toSi(parsedCoords[1], 'length', system),
          z: toSi(parsedCoords[2], 'length', system),
        },
        velocity: {
          x: toSi(parsedCoords[3], 'velocity', system),
          y: toSi(parsedCoords[4], 'velocity', system),
          z: toSi(parsedCoords[5], 'velocity', system),
        },
      })
    }
  })

  // 完全重合的天体在软化引力下不会除零，但初速度相同会导致轨道退化，按契约提前拦截。
  for (let i = 0; i < bodies.length; i += 1) {
    for (let j = i + 1; j < bodies.length; j += 1) {
      const a = bodies[i].position
      const b = bodies[j].position
      if (a.x === b.x && a.y === b.y && a.z === b.z) {
        issues.push(
          issue(`bodies[${j}].position`, 'COINCIDENT_BODIES', `与天体 ${i + 1} 初始位置完全重合。`),
        )
      }
    }
  }

  const timeStep = parseNumberInput(draft.timeStep)
  if (timeStep === null || timeStep <= 0) {
    issues.push(issue('timeStepSeconds', 'INVALID_TIME_STEP', '时间步长必须为正数。'))
  }

  const gravitationalConstant = parseNumberInput(draft.gravitationalConstant)
  if (gravitationalConstant === null || gravitationalConstant <= 0) {
    issues.push(
      issue('gravitationalConstant', 'INVALID_GRAVITATIONAL_CONSTANT', '引力常数必须为正数。'),
    )
  }

  const softeningLength = parseNumberInput(draft.softeningLength)
  if (softeningLength === null || softeningLength < 0) {
    issues.push(issue('softeningLengthMeters', 'INVALID_SOFTENING_LENGTH', '软化长度不能为负数。'))
  }

  const wantsMaxSteps = draft.endCondition === 'MAX_STEPS' || draft.endCondition === 'BOTH'
  const wantsTargetTime = draft.endCondition === 'TARGET_TIME' || draft.endCondition === 'BOTH'

  let maxSteps: number | null = null
  if (wantsMaxSteps) {
    const parsed = parseNumberInput(draft.maxSteps)
    if (parsed === null || !Number.isInteger(parsed) || parsed < 1) {
      issues.push(issue('maxSteps', 'MAX_STEPS_OUT_OF_RANGE', '最大步数必须是不小于 1 的整数。'))
    } else {
      maxSteps = parsed
    }
  }

  let targetTime: number | null = null
  if (wantsTargetTime) {
    const parsed = parseNumberInput(draft.targetSimulationTime)
    if (parsed === null || parsed <= 0) {
      issues.push(
        issue('targetSimulationTimeSeconds', 'TARGET_TIME_OUT_OF_RANGE', '目标模拟时间必须为正数。'),
      )
    } else {
      targetTime = toSi(parsed, 'time', system)
    }
  }

  if (maxSteps === null && targetTime === null) {
    issues.push(
      issue('maxSteps', 'MISSING_END_CONDITION', '必须提供最大步数或目标模拟时间作为结束条件。'),
    )
  }

  const hasError = issues.some((item) => item.severity === 'ERROR')
  if (hasError || timeStep === null || gravitationalConstant === null || softeningLength === null) {
    return { config: null, issues }
  }

  return {
    config: {
      name: draft.name.trim() === '' ? undefined : draft.name.trim(),
      bodies,
      timeStepSeconds: toSi(timeStep, 'time', system),
      gravitationalConstant,
      softeningLengthMeters: toSi(softeningLength, 'length', system),
      maxSteps,
      targetSimulationTimeSeconds: targetTime,
    },
    issues,
  }
}

export function draftsEqual(a: ConfigDraft, b: ConfigDraft): boolean {
  return JSON.stringify(stripRowIds(a)) === JSON.stringify(stripRowIds(b))
}

function stripRowIds(draft: ConfigDraft) {
  return {
    ...draft,
    bodies: draft.bodies.map(({ rowId: _rowId, ...rest }) => rest),
  }
}