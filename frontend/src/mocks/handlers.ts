/**
 * MSW REST 处理器。
 *
 * 覆盖契约中的全部 REST 端点，并按契约返回错误码与状态码，
 * 使前端可以在没有 Java 服务时验证正常流程与失败分支。
 * 含 1.1 契约的量纲化风险 Warning、历史范围查询与回放任务接口。
 */
import { HttpResponse, http } from 'msw'
import type {
  ApiErrorBody,
  ApiErrorCode,
  ExperimentActionRequest,
  ExperimentCreateRequest,
  ReplayJobCreateRequest,
  RiskLevel,
  SimulationConfig,
  ValidationIssue,
  ValidationGuidance,
  ValidationResult,
} from '../contracts'
import { MAX_BODY_COUNT, MIN_BODY_COUNT, isActionAllowed } from '../contracts'
import {
  advance,
  allRecords,
  createRecord,
  createReplayJob,
  deleteRecord,
  deleteReplayJob,
  getHistorySlice,
  getRecord,
  getReplayJob,
  mockPresets,
  normalizeConfig,
  pendingReplayJobCount,
  reorder,
  replayJobResponse,
  scheduleReplayJobs,
  toExperiment,
  toSummary,
} from './mockRepository'
import { startMockScheduler } from './mockScheduler'

const BASE = '/api/v1'
/** Java Double.MIN_NORMAL，作为 rEff 下限，避免极小间距导致周期/逃逸速度溢出。 */
const MIN_NORMAL = 2.2250738585072014e-308

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
    riskLevel: RiskLevel | null = null,
    guidance: ValidationGuidance | null = null,
  ) => issues.push({ field, code, message, severity, riskLevel, guidance })

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

  // 量纲化风险规则（§5.2）只在强制校验所需值均有效后执行。
  if (!issues.some((item) => item.severity === 'ERROR')) {
    addRiskWarnings(config, push)
  }

  const valid = !issues.some((item) => item.severity === 'ERROR')
  const normalized = valid ? normalizeConfig(config) : null
  const estimatedSteps = valid
    ? Math.min(
        config.maxSteps ?? Number.POSITIVE_INFINITY,
        config.targetSimulationTimeSeconds == null
          ? Number.POSITIVE_INFINITY
          : Math.ceil(config.targetSimulationTimeSeconds / config.timeStepSeconds),
      )
    : null
  let nearest: { distance: number; ids: string[] } | null = null
  if (normalized) {
    for (let i = 0; i < normalized.bodies.length; i += 1) {
      for (let j = i + 1; j < normalized.bodies.length; j += 1) {
        const a = normalized.bodies[i]
        const b = normalized.bodies[j]
        const distance = Math.hypot(
          b.position.x - a.position.x,
          b.position.y - a.position.y,
          b.position.z - a.position.z,
        )
        if (!nearest || distance < nearest.distance) nearest = { distance, ids: [a.id!, b.id!] }
      }
    }
  }
  const maxCandidate = config.maxSteps ?? Number.POSITIVE_INFINITY
  const targetCandidate = config.targetSimulationTimeSeconds == null
    ? Number.POSITIVE_INFINITY
    : Math.ceil(config.targetSimulationTimeSeconds / config.timeStepSeconds)
  return {
    valid,
    issues,
    normalizedConfig: normalized,
    estimatedSteps: Number.isFinite(estimatedSteps) ? estimatedSteps : null,
    configSummary: valid ? {
      estimatedSteps: Number.isFinite(estimatedSteps) ? estimatedSteps : null,
      estimatedSimulationTimeSeconds: Number.isFinite(estimatedSteps)
        ? estimatedSteps! * config.timeStepSeconds
        : null,
      limitingEndCondition: maxCandidate === targetCandidate
        ? 'BOTH'
        : maxCandidate < targetCandidate ? 'MAX_STEPS' : 'TARGET_TIME',
      initialMinimumPairDistanceMeters: nearest?.distance ?? null,
      initialMinimumPairBodyIds: nearest?.ids ?? [],
      softeningToInitialDistanceRatio: nearest && nearest.distance > 0
        ? config.softeningLengthMeters / nearest.distance
        : null,
    } : null,
  }
}

interface RiskPair {
  i: number
  j: number
  r: number
  rEff: number
  period: number
  moveRate: number
  speedRate: number
}

/**
 * 量纲化风险规则（§5.2）：对每对天体计算 rEff/μ/vRel/period/moveRate/vEscape/speedRate，
 * 每种风险码只返回最严重的一条 Warning，消息带最危险天体名称与计算比值。
 */
function addRiskWarnings(
  config: SimulationConfig,
  push: (
    field: string,
    code: ValidationIssue['code'],
    message: string,
    severity: ValidationIssue['severity'],
    riskLevel: RiskLevel,
    guidance?: ValidationGuidance | null,
  ) => void,
): void {
  const bodies = config.bodies
  if (!Array.isArray(bodies) || bodies.length < 2) return
  const dt = config.timeStepSeconds
  const G = config.gravitationalConstant
  const eps = config.softeningLengthMeters
  if (!Number.isFinite(dt) || dt <= 0) return
  if (!Number.isFinite(G) || G <= 0) return
  if (!Number.isFinite(eps) || eps < 0) return
  const allFinite = bodies.every(
    (body) =>
      Number.isFinite(body.massKg) &&
      body.massKg > 0 &&
      Number.isFinite(body.position?.x) &&
      Number.isFinite(body.position?.y) &&
      Number.isFinite(body.position?.z) &&
      Number.isFinite(body.velocity?.x) &&
      Number.isFinite(body.velocity?.y) &&
      Number.isFinite(body.velocity?.z),
  )
  if (!allFinite) return

  const pairs: RiskPair[] = []
  for (let i = 0; i < bodies.length; i += 1) {
    for (let j = i + 1; j < bodies.length; j += 1) {
      const a = bodies[i]
      const b = bodies[j]
      const r = Math.hypot(
        b.position.x - a.position.x,
        b.position.y - a.position.y,
        b.position.z - a.position.z,
      )
      const rEff = Math.max(r, eps, MIN_NORMAL)
      const mu = G * (a.massKg + b.massKg)
      const vRel = Math.hypot(
        b.velocity.x - a.velocity.x,
        b.velocity.y - a.velocity.y,
        b.velocity.z - a.velocity.z,
      )
      const period = 2 * Math.PI * Math.sqrt((rEff * rEff * rEff) / mu)
      const moveRate = (vRel * dt) / rEff
      const vEscape = Math.sqrt((2 * mu) / rEff)
      const speedRate = vRel / vEscape
      pairs.push({ i, j, r, rEff, period, moveRate, speedRate })
    }
  }
  const minPeriodPair = pairs.reduce((acc, pair) => (pair.period < acc.period ? pair : acc), pairs[0])
  const minDistancePair = pairs.reduce((acc, pair) => (pair.r < acc.r ? pair : acc), pairs[0])
  const worstMovePair = pairs.reduce((acc, pair) => (pair.moveRate > acc.moveRate ? pair : acc), pairs[0])
  const worstSpeedPair = pairs.reduce((acc, pair) => (pair.speedRate > acc.speedRate ? pair : acc), pairs[0])
  const name = (index: number) => bodies[index].name || `天体 ${index + 1}`

  // TIME_STEP_TOO_LARGE：HIGH 优先。
  const dtHigh = dt > minPeriodPair.period / 20 || worstMovePair.moveRate > 0.2
  const dtCaution = dt > minPeriodPair.period / 100 || worstMovePair.moveRate > 0.05
  if (dtHigh || dtCaution) {
    const stepsPerPeriod = minPeriodPair.period / dt
    const periodRatio = dt / minPeriodPair.period
    const suggestedDt = dt * Math.min(
      1,
      0.009 / periodRatio,
      worstMovePair.moveRate > 0 ? 0.045 / worstMovePair.moveRate : 1,
    )
    const ids = (pair: RiskPair) => [bodies[pair.i].id!, bodies[pair.j].id!]
    push(
      'timeStepSeconds',
      'TIME_STEP_TOO_LARGE',
      `时间步长 ${dt.toExponential(3)} s 相对最近轨道周期 ${minPeriodPair.period.toExponential(3)} s 偏大（${name(minPeriodPair.i)} 与 ${name(minPeriodPair.j)}，约每周期 ${stepsPerPeriod.toFixed(1)} 步、单步位移/间距约 ${worstMovePair.moveRate.toExponential(3)}），长期积分可能出现明显能量漂移；减小步长会增加计算量但通常改善稳定性。`,
      'WARNING',
      dtHigh ? 'HIGH' : 'CAUTION',
      {
        observation: '当前时间步长相对系统中最快的局部运动偏大。',
        impact: '一次积分可能跨过过多轨道变化，使守恒量漂移增大，甚至导致数值失败。',
        evidence: [
          { code: 'TIME_STEP_SECONDS', value: dt, referenceValue: suggestedDt, ratio: null, bodyIds: [] },
          { code: 'LOCAL_PERIOD_SECONDS', value: minPeriodPair.period, referenceValue: minPeriodPair.period * 0.01, ratio: periodRatio, bodyIds: ids(minPeriodPair) },
          { code: 'ONE_STEP_RELATIVE_MOVEMENT_METERS', value: worstMovePair.moveRate * worstMovePair.rEff, referenceValue: worstMovePair.rEff, ratio: worstMovePair.moveRate, bodyIds: ids(worstMovePair) },
        ],
        primaryAction: {
          code: 'REDUCE_TIME_STEP', mode: 'APPLY_PATCH', label: '减小时间步长',
          rationale: '让局部周期比例和单步位移比例回到注意阈值以内。',
          tradeoff: '为保持相同模拟时长，积分步数和计算量会增加。',
          configPatch: { timeStepSeconds: suggestedDt, maxSteps: null },
          adjustmentPolicy: 'PRESERVE_SIMULATION_DURATION',
        },
        alternatives: [{
          code: 'REVIEW_FASTEST_PAIR', mode: 'MANUAL_REVIEW', label: '检查最快变化的天体对',
          rationale: '确认当前初始位置和速度是否符合实验目的。',
          tradeoff: '修改初始条件会改变研究问题。', configPatch: null, adjustmentPolicy: null,
        }],
      },
    )
  }

  // INITIAL_DISTANCE_TOO_SMALL：只返回 HIGH。
  const fiveEps = 5 * eps
  if (eps > 0 && minDistancePair.r < fiveEps) {
    push(
      `bodies[${minDistancePair.j}].position`,
      'INITIAL_DISTANCE_TOO_SMALL',
      `${name(minDistancePair.i)} 与 ${name(minDistancePair.j)} 初始距离 ${minDistancePair.r.toExponential(3)} m 小于 5ε=${fiveEps.toExponential(3)} m，启动即进入近距离事件，而非一定发生碰撞。`,
      'WARNING',
      'HIGH',
    )
  }

  // INITIAL_SPEED_HIGH：HIGH 优先。
  const speedHigh = worstSpeedPair.speedRate > 5
  const speedCaution = worstSpeedPair.speedRate > 2
  if (speedHigh || speedCaution) {
    push(
      `bodies[${worstSpeedPair.j}].velocity`,
      'INITIAL_SPEED_HIGH',
      `${name(worstSpeedPair.i)} 与 ${name(worstSpeedPair.j)} 相对速度与逃逸速度之比约 ${worstSpeedPair.speedRate.toFixed(2)}，可能是合法的非束缚/逃逸初始条件，也可能快速解体。`,
      'WARNING',
      speedHigh ? 'HIGH' : 'CAUTION',
    )
  }

  // SOFTENING_TOO_SMALL：CAUTION 常发；HIGH 仅在与 HIGH 时间步风险同时出现时。
  const epsRatio = eps / Math.max(minDistancePair.r, MIN_NORMAL)
  const softeningCaution = eps === 0 || epsRatio < 1e-6
  if (softeningCaution) {
    const zeroNote = eps === 0 ? '，且近遇阈值为 0' : ''
    push(
      'softeningLengthMeters',
      'SOFTENING_TOO_SMALL',
      `软化长度 ${eps.toExponential(3)} m 相对最近初始间距 ${minDistancePair.r.toExponential(3)} m 过小（ε/rMin≈${epsRatio.toExponential(3)}）${zeroNote}，近接保护弱，可能放大近遇处数值误差。`,
      'WARNING',
      dtHigh ? 'HIGH' : 'CAUTION',
    )
  }

  // SOFTENING_TOO_LARGE：只返回 HIGH。
  if (epsRatio > 0.1) {
    push(
      'softeningLengthMeters',
      'SOFTENING_TOO_LARGE',
      `软化长度 ${eps.toExponential(3)} m 相对最近初始间距 ${minDistancePair.r.toExponential(3)} m 过大（ε/rMin≈${epsRatio.toExponential(3)}），近距离引力会被明显抹平，结果可能偏离目标物理模型。`,
      'WARNING',
      'HIGH',
    )
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
    let lineage = null
    if (payload.retryContext) {
      const sourceRecord = getRecord(payload.retryContext.sourceExperimentId)
      if (!sourceRecord) return errorResponse(400, 'INVALID_RETRY_CONTEXT', '源运行记录不存在。')
      const source = toExperiment(sourceRecord)
      const recommendation = source.healthReport?.recommendations.find(
        (item) => item.code === payload.retryContext?.recommendationCode,
      )
      if (!recommendation) return errorResponse(400, 'INVALID_RETRY_CONTEXT', '源运行记录没有当前建议。')
      const after = normalizeConfig(payload.config)
      const beforeSteps = Math.min(
        source.config.maxSteps ?? Number.POSITIVE_INFINITY,
        source.config.targetSimulationTimeSeconds == null ? Number.POSITIVE_INFINITY : Math.ceil(source.config.targetSimulationTimeSeconds / source.config.timeStepSeconds),
      )
      const afterSteps = Math.min(
        after.maxSteps ?? Number.POSITIVE_INFINITY,
        after.targetSimulationTimeSeconds == null ? Number.POSITIVE_INFINITY : Math.ceil(after.targetSimulationTimeSeconds / after.timeStepSeconds),
      )
      const changedFields = [
        source.config.timeStepSeconds !== after.timeStepSeconds ? 'timeStepSeconds' : null,
        source.config.maxSteps !== after.maxSteps ? 'maxSteps' : null,
        source.config.targetSimulationTimeSeconds !== after.targetSimulationTimeSeconds ? 'targetSimulationTimeSeconds' : null,
        source.config.softeningLengthMeters !== after.softeningLengthMeters ? 'softeningLengthMeters' : null,
        source.config.gravitationalConstant !== after.gravitationalConstant ? 'gravitationalConstant' : null,
        JSON.stringify(source.config.bodies) !== JSON.stringify(after.bodies) ? 'bodies' : null,
      ].filter((item): item is string => item != null)
      lineage = {
        sourceExperimentId: source.id,
        sourceExperimentName: source.name,
        rootExperimentId: source.lineage?.rootExperimentId ?? source.id,
        retryDepth: (source.lineage?.retryDepth ?? 0) + 1,
        recommendationCode: payload.retryContext.recommendationCode,
        strategy: payload.retryContext.strategy,
        beforeTimeStepSeconds: source.config.timeStepSeconds,
        afterTimeStepSeconds: after.timeStepSeconds,
        beforeMaxSteps: source.config.maxSteps ?? null,
        afterMaxSteps: after.maxSteps ?? null,
        beforeTargetSimulationTimeSeconds: source.config.targetSimulationTimeSeconds ?? null,
        afterTargetSimulationTimeSeconds: after.targetSimulationTimeSeconds ?? null,
        beforeEstimatedSimulationTimeSeconds: beforeSteps * source.config.timeStepSeconds,
        afterEstimatedSimulationTimeSeconds: afterSteps * after.timeStepSeconds,
        beforeEstimatedSteps: beforeSteps,
        afterEstimatedSteps: afterSteps,
        changedFields,
        sourceHealthStatus: source.healthStatus ?? null,
      }
    }
    const record = createRecord(payload.config, payload.name, lineage)
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

  http.get(`${BASE}/experiments/:id/history`, ({ params, request }) => {
    const record = getRecord(String(params.id))
    if (!record) return errorResponse(404, 'EXPERIMENT_NOT_FOUND', '实验不存在。')
    const url = new URL(request.url)
    const readInt = (name: string): number | null => {
      const raw = url.searchParams.get(name)
      if (raw === null) return null
      const value = Number(raw)
      return Number.isSafeInteger(value) ? value : NaN
    }
    const fromRaw = readInt('fromStep')
    const toRaw = readInt('toStep')
    const maxRaw = readInt('maxPoints')
    if (
      (fromRaw !== null && Number.isNaN(fromRaw)) ||
      (toRaw !== null && Number.isNaN(toRaw)) ||
      (maxRaw !== null && Number.isNaN(maxRaw))
    ) {
      return errorResponse(400, 'MALFORMED_REQUEST', '历史查询参数必须是整数。')
    }
    const fromStep = fromRaw ?? 0
    const maxPoints = maxRaw ?? 1000
    const toStep = toRaw ?? record.state.step
    if (fromStep < 0 || toStep < fromStep || toStep > record.state.step || maxPoints < 2 || maxPoints > 2000) {
      return errorResponse(400, 'MALFORMED_REQUEST', '历史查询参数超出允许范围（fromStep>=0，toStep>=fromStep 且不超过当前步，maxPoints 2～2000）。')
    }
    return HttpResponse.json(getHistorySlice(record, fromStep, toStep, maxPoints))
  }),

  http.post(`${BASE}/experiments/:id/replay-jobs`, async ({ params, request }) => {
    const record = getRecord(String(params.id))
    if (!record) return errorResponse(404, 'EXPERIMENT_NOT_FOUND', '实验不存在。')
    const payload = (await request.json()) as ReplayJobCreateRequest
    const targetStep = payload?.targetStep
    if (!Number.isInteger(targetStep) || (targetStep as number) < 0 || (targetStep as number) > record.state.step) {
      return errorResponse(400, 'MALFORMED_REQUEST', 'targetStep 必须是 [0, 当前步] 内的整数。')
    }
    if (pendingReplayJobCount() >= 8) {
      return errorResponse(429, 'REPLAY_QUEUE_FULL', '回放任务队列已满，请稍后重试。')
    }
    const { job, httpStatus } = createReplayJob(record, targetStep)
    scheduleReplayJobs()
    return HttpResponse.json(replayJobResponse(job), { status: httpStatus })
  }),

  http.get(`${BASE}/experiments/:id/replay-jobs/:jobId`, ({ params }) => {
    const record = getRecord(String(params.id))
    if (!record) return errorResponse(404, 'EXPERIMENT_NOT_FOUND', '实验不存在。')
    const job = getReplayJob(record, String(params.jobId))
    if (!job) return errorResponse(404, 'REPLAY_JOB_NOT_FOUND', '回放任务不存在或已过期。')
    return HttpResponse.json(replayJobResponse(job))
  }),

  http.delete(`${BASE}/experiments/:id/replay-jobs/:jobId`, ({ params }) => {
    const record = getRecord(String(params.id))
    if (!record) return errorResponse(404, 'EXPERIMENT_NOT_FOUND', '实验不存在。')
    const deleted = deleteReplayJob(record, String(params.jobId))
    if (!deleted) return errorResponse(404, 'REPLAY_JOB_NOT_FOUND', '回放任务不存在或已过期。')
    return new HttpResponse(null, { status: 204 })
  }),
]
