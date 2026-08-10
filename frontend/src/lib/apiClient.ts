/**
 * REST 客户端。
 *
 * 约定：
 * - 所有请求体与响应体都是契约里的 SI 数值，不做单位换算。
 * - 服务端错误统一抛出 ApiError，界面据此展示错误码与校验问题。
 * - 网络与解析失败也包装成 ApiError，避免调用方处理两套异常类型。
 */
import type {
  ApiErrorBody,
  ApiErrorCode,
  DeleteResult,
  Experiment,
  ExperimentActionRequest,
  ExperimentCreateRequest,
  ExperimentStatus,
  ExperimentSummary,
  Preset,
  ReportData,
  SimulationConfig,
  ValidationIssue,
  ValidationResult,
} from '../contracts'

export class ApiError extends Error {
  readonly code: ApiErrorCode | 'NETWORK_ERROR' | 'UNEXPECTED_RESPONSE'
  readonly status: number
  readonly issues: ValidationIssue[]
  readonly timestamp: string

  constructor(params: {
    code: ApiErrorCode | 'NETWORK_ERROR' | 'UNEXPECTED_RESPONSE'
    message: string
    status: number
    issues?: ValidationIssue[] | null
    timestamp?: string
  }) {
    super(params.message)
    this.name = 'ApiError'
    this.code = params.code
    this.status = params.status
    this.issues = params.issues ?? []
    this.timestamp = params.timestamp ?? new Date().toISOString()
  }

  /** 队列或状态冲突时界面需要刷新列表而不是提示用户重试。 */
  get isConflict(): boolean {
    return this.status === 409
  }

  get isNotFound(): boolean {
    return this.status === 404
  }
}

export interface TrajectoryCsv {
  csv: string
  /** 相邻采样点之间的模拟步数，报告与下载说明必须显示。 */
  sampleStride: number | null
  sampleCount: number | null
  filename: string
}

const DEFAULT_BASE_URL = '/api/v1'

function baseUrl(): string {
  const configured = import.meta.env.VITE_API_BASE_URL
  return (configured && configured.trim() !== '' ? configured : DEFAULT_BASE_URL).replace(/\/$/, '')
}

function isApiErrorBody(value: unknown): value is ApiErrorBody {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Record<string, unknown>
  return typeof candidate.code === 'string' && typeof candidate.message === 'string'
}

async function toApiError(response: Response): Promise<ApiError> {
  let body: unknown = null
  try {
    body = await response.json()
  } catch {
    body = null
  }
  if (isApiErrorBody(body)) {
    return new ApiError({
      code: body.code,
      message: body.message,
      status: response.status,
      issues: body.issues ?? [],
      timestamp: body.timestamp,
    })
  }
  return new ApiError({
    code: 'UNEXPECTED_RESPONSE',
    message: `服务端返回了无法解析的响应（HTTP ${response.status}）。`,
    status: response.status,
  })
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${baseUrl()}${path}`, {
      ...init,
      headers: {
        Accept: 'application/json',
        ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
        ...init?.headers,
      },
    })
  } catch (error) {
    throw new ApiError({
      code: 'NETWORK_ERROR',
      message: error instanceof Error ? `无法连接本地服务：${error.message}` : '无法连接本地服务。',
      status: 0,
    })
  }

  if (!response.ok) {
    throw await toApiError(response)
  }

  if (response.status === 204) {
    return undefined as T
  }

  try {
    return (await response.json()) as T
  } catch {
    throw new ApiError({
      code: 'UNEXPECTED_RESPONSE',
      message: '服务端响应不是合法的 JSON。',
      status: response.status,
    })
  }
}

export const api = {
  listPresets(): Promise<Preset[]> {
    return request<Preset[]>('/presets')
  },

  validateConfig(config: SimulationConfig): Promise<ValidationResult> {
    return request<ValidationResult>('/configs/validate', {
      method: 'POST',
      body: JSON.stringify(config),
    })
  },

  listExperiments(statuses?: ExperimentStatus[]): Promise<ExperimentSummary[]> {
    const query = statuses && statuses.length > 0 ? `?status=${encodeURIComponent(statuses.join(','))}` : ''
    return request<ExperimentSummary[]>(`/experiments${query}`)
  },

  createExperiment(payload: ExperimentCreateRequest): Promise<Experiment> {
    return request<Experiment>('/experiments', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },

  getExperiment(id: string): Promise<Experiment> {
    return request<Experiment>(`/experiments/${id}`)
  },

  updateExperiment(id: string, payload: ExperimentCreateRequest): Promise<Experiment> {
    return request<Experiment>(`/experiments/${id}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    })
  },

  deleteExperiment(id: string): Promise<DeleteResult> {
    return request<DeleteResult>(`/experiments/${id}`, { method: 'DELETE' })
  },

  submitAction(id: string, payload: ExperimentActionRequest): Promise<Experiment> {
    return request<Experiment>(`/experiments/${id}/actions`, {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },

  reorderQueue(experimentIds: string[]): Promise<ExperimentSummary[]> {
    return request<ExperimentSummary[]>('/queue', {
      method: 'PATCH',
      body: JSON.stringify({ experimentIds }),
    })
  },

  exportConfig(id: string): Promise<SimulationConfig> {
    return request<SimulationConfig>(`/experiments/${id}/exports/config`)
  },

  getReportData(id: string): Promise<ReportData> {
    return request<ReportData>(`/experiments/${id}/report-data`)
  },

  /** 轨迹 CSV 需要读取采样步长响应头，因此不复用 request()。 */
  async exportTrajectory(id: string): Promise<TrajectoryCsv> {
    let response: Response
    try {
      response = await fetch(`${baseUrl()}/experiments/${id}/exports/trajectory`, {
        headers: { Accept: 'text/csv' },
      })
    } catch (error) {
      throw new ApiError({
        code: 'NETWORK_ERROR',
        message: error instanceof Error ? `无法连接本地服务：${error.message}` : '无法连接本地服务。',
        status: 0,
      })
    }
    if (!response.ok) {
      throw await toApiError(response)
    }
    const csv = await response.text()
    const stride = Number(response.headers.get('X-Sample-Stride'))
    const count = Number(response.headers.get('X-Sample-Count'))
    return {
      csv,
      sampleStride: Number.isFinite(stride) && stride > 0 ? stride : null,
      sampleCount: Number.isFinite(count) && count >= 0 ? count : null,
      filename: filenameFromDisposition(response.headers.get('Content-Disposition')) ?? `experiment-${id}-trajectory.csv`,
    }
  },
}

function filenameFromDisposition(header: string | null): string | null {
  if (!header) return null
  const utf8Match = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (utf8Match) {
    try {
      return decodeURIComponent(utf8Match[1])
    } catch {
      return null
    }
  }
  const plainMatch = /filename="?([^";]+)"?/i.exec(header)
  return plainMatch ? plainMatch[1] : null
}