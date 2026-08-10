/**
 * MSW REST 处理器。
 *
 * 覆盖契约中的全部 REST 端点，并按契约返回错误码与状态码，
 * 使前端可以在没有 Java 服务时验证正常流程与失败分支。
 */
import { HttpResponse, http } from 'msw'
import type {
  ApiErrorBody,
  ApiErrorCode,
  ExperimentActionRequest,
  ExperimentCreateRequest,
  SimulationConfig,
  ValidationIssue,
  ValidationResult,
} from '../contracts'
import { MAX_BODY_COUNT, MIN_BODY_COUNT, isActionAllowed } from '../contracts'
import {
  advance,
  allRecords,
  createRecord,
  deleteRecord,
  getRecord,
  mockPresets,
  normalizeConfig,
  reorder,
  toExperiment,
  toSummary,
} from './mockRepository'
import { startMockScheduler } from './mockScheduler'

const BASE = '/api/v1'

function errorResponse(status: number, code: ApiErrorCode, message: string, issues?: ValidationIssue[]) {
  const body: ApiErrorBody = {
    code,
    message,
    timestamp: new Date().toISOString(),
    issues: issues ?? null,
  }
  return HttpResponse.json(body, { status })
}

/** 与后端校验规则保持一致的 Mock 实现。 */
export function validateConfig(config: SimulationConfig): ValidationResult {
  const issues: ValidationIssue[] = []
  const push = (
    field: string,
    code: ValidationIssue['code'],
    message: string,
    severity: ValidationIssue['severity'] = 'ERROR',
  ) => issues.push({ field, code, message, severity })

  if (!Array.isArray(config.bodies) || config.bodies.length < MIN_BODY_COUNT || config.bodies.length > MAX_BODY_COUNT) {
    push('bodies', 'BODY_COUNT_OUT_OF_RANGE', `天体数量必须介于 ${MIN_BODY_COUNT} 与 ${MAX_BODY_COUNT} 之间。`)
  }

  const seen = new Set<string>()
  config.bodies?.forEach((body, index) => {
    const prefix = `bodies[${index}]`
    if (!body.name || body.name.trim() === '') {
      push(`${prefix}.name`, 'MISSING_BODY_NAME', '天体名称不能为空。')
    }
    if (body.color && !/^#[0-9a-fA-F]{6}$/.test(body.color)) {
      push(`${prefix}.color`, 'INVALID_COLOR', '颜色必须是 #RRGGBB 形式。')
    }
    if (body.id) {
      if (seen.has(body.id)) push(`${prefix}.id`, 'DUPLICATE_BODY_ID', '天体 ID 重复。')
      seen.add(body.id)
    }
    if (!Number.isFinite(body.massKg) || body.massKg <= 0) {
      push(`${prefix}.massKg`, 'INVALID_MASS', '质量必须为正的有限数。')
    }
    for (const axis of ['x', 'y', 'z'] as const) {
      if (!Number.isFinite(body.position?.[axis])) {
        push(`${prefix}.position.${axis}`, 'NON_FINITE_VALUE', '位置分量必须是有限数。')
      }
      if (!Number.isFinite(body.velocity?.[axis])) {
        push(`${prefix}.velocity.${axis}`, 'NON_FINITE_VALUE', '速度分量必须是有限数。')
      }
    }
  })

  for (let i = 0; i < (config.bodies?.length ?? 0); i += 1) {
    for (let j = i + 1; j < config.bodies.length; j += 1) {
      const a = config.bodies[i].position
      const b = config.bodies[j].position
      if (a && b && a.x === b.x && a.y === b.y && a.z === b.z) {
        push(`bodies[${j}].position`, 'COINCIDENT_BODIES', `与天体 ${i + 1} 初始位置完全重合。`)
      }
    }
  }

  if (!Number.isFinite(config.timeStepSeconds) || config.timeStepSeconds <= 0) {
    push('timeStepSeconds', 'INVALID_TIME_STEP', '时间步长必须为正数。')
  }
  if (!Number.isFinite(config.gravitationalConstant) || config.gravitationalConstant <= 0) {
    push('gravitationalConstant', 'INVALID_GRAVITATIONAL_CONSTANT', '引力常数必须为正数。')
  }
  if (!Number.isFinite(config.softeningLengthMeters) || config.softeningLengthMeters < 0) {
    push('softeningLengthMeters', 'INVALID_SOFTENING_LENGTH', '软化长度不能为负数。')
  }

  const hasMaxSteps = config.maxSteps !== null && config.maxSteps !== undefined
  const hasTarget =
    config.targetSimulationTimeSeconds !== null && config.targetSimulationTimeSeconds !== undefined
  if (!hasMaxSteps && !hasTarget) {
    push('maxSteps', 'MISSING_END_CONDITION', '必须提供最大步数或目标模拟时间。')
  }
  if (hasMaxSteps && (!Number.isInteger(config.maxSteps) || (config.maxSteps as number) < 1)) {
    push('maxSteps', 'MAX_STEPS_OUT_OF_RANGE', '最大步数必须是不小于 1 的整数。')
  }
  if (hasTarget && (config.targetSimulationTimeSeconds as number) <= 0) {
    push('targetSimulationTimeSeconds', 'TARGET_TIME_OUT_OF_RANGE', '目标模拟时间必须为正数。')
  }

  // 时间步长相对最近间距过大时给出警告，模拟后端的稳定性提示。
  if (config.bodies?.length >= 2 && Number.isFinite(config.timeStepSeconds)) {
    let minDistance = Number.POSITIVE_INFINITY
    for (let i = 0; i < config.bodies.length; i += 1) {
      for (let j = i + 1; j < config.bodies.length; j += 1) {
        const a = config.bodies[i].position
        const b = config.bodies[j].position
        if (!a || !b) continue
        minDistance = Math.min(minDistance, Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z))
      }
    }
    if (Number.isFinite(minDistance) && minDistance > 0 && config.timeStepSeconds > 0) {
      const maxSpeed = Math.max(
        ...config.bodies.map((body) =>
          Math.hypot(body.velocity?.x ?? 0, body.velocity?.y ?? 0, body.velocity?.z ?? 0),
        ),
      )
      if (maxSpeed > 0 && maxSpeed * config.timeStepSeconds > minDistance * 0.1) {
        push(
          'timeStepSeconds',
          'INVALID_TIME_STEP',
          '时间步长相对最近天体间距偏大，长期积分可能出现明显能量漂移。',
          'WARNING',
        )
      }
    }
  }

  const valid = !issues.some((item) => item.severity === 'ERROR')
  return {
    valid,
    issues,
    normalizedConfig: valid ? normalizeConfig(config) : null,
    estimatedSteps: valid
      ? (config.maxSteps ??
        (config.targetSimulationTimeSeconds
          ? Math.ceil(config.targetSimulationTimeSeconds / config.timeStepSeconds)
          : null))
      : null,
  }
}

function csvForRecord(id: string): { csv: string; stride: number; count: number } | null {
  const record = getRecord(id)
  if (!record) return null
  const nameById = new Map(record.config.bodies.map((body) => [body.id ?? '', body.name]))
  const header = 'step,timeSeconds,bodyId,bodyName,x,y,z,vx,vy,vz'
  const rows: string[] = [header]
  for (const sample of record.samples) {
    for (const body of sample.bodies) {
      rows.push(
        [
          sample.step,
          sample.simulationTimeSeconds,
          body.id,
          JSON.stringify(nameById.get(body.id) ?? ''),
          body.position.x,
          body.position.y,
          body.position.z,
          body.velocity.x,
          body.velocity.y,
          body.velocity.z,
        ].join(','),
      )
    }
  }
  return { csv: rows.join('\n'), stride: record.sampleStride, count: record.samples.length }
}

export const handlers = [
  http.get(`${BASE}/presets`, () => HttpResponse.json(mockPresets)),

  http.post(`${BASE}/configs/validate`, async ({ request }) => {
    const config = (await request.json()) as SimulationConfig
    return HttpResponse.json(validateConfig(config))
  }),

  http.get(`${BASE}/experiments`, ({ request }) => {
    const url = new URL(request.url)
    const statusParam = url.searchParams.get('status')
    const wanted = statusParam ? statusParam.split(',').map((item) => item.trim()) : null
    const summaries = allRecords()
      .sort((a, b) => a.queueOrder - b.queueOrder)
      .map(toSummary)
      .filter((item) => !wanted || wanted.includes(item.status))
    return HttpResponse.json(summaries)
  }),

  http.post(`${BASE}/experiments`, async ({ request }) => {
    const payload = (await request.json()) as ExperimentCreateRequest
    if (!payload?.config) {
      return errorResponse(400, 'MALFORMED_REQUEST', '请求缺少 config 字段。')
    }
    const validation = validateConfig(payload.config)
    if (!validation.valid) {
      return errorResponse(400, 'VALIDATION_FAILED', '配置未通过校验。', validation.issues)
    }
    const record = createRecord(payload.config, payload.name)
    startMockScheduler()
    return HttpResponse.json(toExperiment(record), {
      status: 201,
      headers: { Location: `${BASE}/experiments/${record.id}` },
    })
  }),

  http.get(`${BASE}/experiments/:id`, ({ params }) => {
    const record = getRecord(String(params.id))
    if (!record) return errorResponse(404, 'EXPERIMENT_NOT_FOUND', '实验不存在。')
    return HttpResponse.json(toExperiment(record))
  }),

  http.put(`${BASE}/experiments/:id`, async ({ params, request }) => {
    const record = getRecord(String(params.id))
    if (!record) return errorResponse(404, 'EXPERIMENT_NOT_FOUND', '实验不存在。')
    if (record.status !== 'QUEUED') {
      return errorResponse(409, 'EXPERIMENT_NOT_EDITABLE', '只有排队中的实验可以直接编辑，请使用重启动作。')
    }
    const payload = (await request.json()) as ExperimentCreateRequest
    const validation = validateConfig(payload.config)
    if (!validation.valid) {
      return errorResponse(400, 'VALIDATION_FAILED', '配置未通过校验。', validation.issues)
    }
    const replaced = createRecord(payload.config, payload.name ?? record.name)
    replaced.queueOrder = record.queueOrder
    deleteRecord(record.id)
    return HttpResponse.json(toExperiment(replaced))
  }),

  http.delete(`${BASE}/experiments/:id`, ({ params }) => {
    const record = getRecord(String(params.id))
    if (!record) return errorResponse(404, 'EXPERIMENT_NOT_FOUND', '实验不存在。')
    if (record.status === 'RUNNING') {
      return errorResponse(409, 'ILLEGAL_STATE_TRANSITION', '正在运行的实验必须先取消。')
    }
    const freed = deleteRecord(record.id)
    return HttpResponse.json({ id: record.id, freedBytes: freed })
  }),

  http.post(`${BASE}/experiments/:id/actions`, async ({ params, request }) => {
    const record = getRecord(String(params.id))
    if (!record) return errorResponse(404, 'EXPERIMENT_NOT_FOUND', '实验不存在。')
    const payload = (await request.json()) as ExperimentActionRequest
    const action = payload?.action
    if (!action) return errorResponse(400, 'MALFORMED_REQUEST', '请求缺少 action 字段。')
    if (action !== 'RESTART' && payload.config) {
      return errorResponse(400, 'UNSUPPORTED_ACTION_PAYLOAD', '只有 RESTART 动作可以携带配置。')
    }
    if (!isActionAllowed(record.status, action)) {
      return errorResponse(
        409,
        'ILLEGAL_STATE_TRANSITION',
        `${record.status} 状态不允许执行 ${action} 动作。`,
      )
    }

    switch (action) {
      case 'PAUSE':
        record.status = 'PAUSED'
        break
      case 'RESUME':
        record.status = 'RUNNING'
        if (!record.startedAt) record.startedAt = new Date().toISOString()
        startMockScheduler()
        break
      case 'STEP':
        advance(record, 1)
        break
      case 'CANCEL':
        record.status = 'CANCELLED'
        record.endReason = 'CANCELLED'
        record.completedAt = new Date().toISOString()
        break
      case 'RESTART': {
        const config = payload.config ?? record.config
        const validation = validateConfig(config)
        if (!validation.valid) {
          return errorResponse(400, 'VALIDATION_FAILED', '配置未通过校验。', validation.issues)
        }
        const replaced = createRecord(config, record.name)
        replaced.queueOrder = record.queueOrder
        deleteRecord(record.id)
        startMockScheduler()
        return HttpResponse.json(toExperiment(replaced))
      }
    }
    record.updatedAt = new Date().toISOString()
    return HttpResponse.json(toExperiment(record))
  }),

  http.patch(`${BASE}/queue`, async ({ request }) => {
    const payload = (await request.json()) as { experimentIds?: string[] }
    const ids = payload?.experimentIds
    if (!Array.isArray(ids)) {
      return errorResponse(400, 'MALFORMED_REQUEST', '请求缺少 experimentIds 数组。')
    }
    const known = new Set(allRecords().map((item) => item.id))
    if (ids.length !== known.size || ids.some((id) => !known.has(id))) {
      return errorResponse(409, 'QUEUE_CONFLICT', '提交的列表必须包含且只包含当前全部实验 ID。')
    }
    reorder(ids)
    const summaries = allRecords()
      .sort((a, b) => a.queueOrder - b.queueOrder)
      .map(toSummary)
    return HttpResponse.json(summaries)
  }),

  http.get(`${BASE}/experiments/:id/exports/config`, ({ params }) => {
    const record = getRecord(String(params.id))
    if (!record) return errorResponse(404, 'EXPERIMENT_NOT_FOUND', '实验不存在。')
    return HttpResponse.json(record.config, {
      headers: {
        'Content-Disposition': `attachment; filename="experiment-${record.id}-config.json"`,
      },
    })
  }),

  http.get(`${BASE}/experiments/:id/exports/trajectory`, ({ params }) => {
    const result = csvForRecord(String(params.id))
    if (!result) return errorResponse(404, 'EXPERIMENT_NOT_FOUND', '实验不存在。')
    return new HttpResponse(result.csv, {
      headers: {
        'Content-Type': 'text/csv; charset=utf-8',
        'X-Sample-Stride': String(result.stride),
        'X-Sample-Count': String(result.count),
        'Content-Disposition': `attachment; filename="experiment-${String(params.id)}-trajectory.csv"`,
      },
    })
  }),

  http.get(`${BASE}/experiments/:id/report-data`, ({ params }) => {
    const record = getRecord(String(params.id))
    if (!record) return errorResponse(404, 'EXPERIMENT_NOT_FOUND', '实验不存在。')
    return HttpResponse.json({
      experiment: toExperiment(record),
      unitSystem: 'SI',
      sampleStride: record.sampleStride,
      samples: record.samples,
      generatedAt: new Date().toISOString(),
    })
  }),
]