import type { ExperimentAction } from '../contracts'

export const ALLOWED_ACTION_LABELS: Record<ExperimentAction, string> = {
  PAUSE: '暂停',
  RESUME: '继续',
  STEP: '单步',
  RESTART: '重启',
  CANCEL: '取消',
}