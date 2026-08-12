import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { SimulationConfig } from '../../contracts'
import {
  advance,
  createRecord,
  createReplayJob,
  deleteReplayJob,
  emitEvent,
  getHistorySlice,
  getRecord,
  getReplayJob,
  pendingReplayJobCount,
  replayJobResponse,
  resetRepository,
  stopReplayJobs,
  upsertEvent,
} from '../mockRepository'
import { resetSchedulerState, stopMockScheduler } from '../mockScheduler'

function baseConfig(): SimulationConfig {
  return {
    name: '回放测试',
    bodies: [
      { name: 'α', color: '#ffc857', massKg: 1e30, position: { x: 0, y: 0, z: 0 }, velocity: { x: 0, y: 0, z: 0 } },
      { name: 'β', color: '#59c3ff', massKg: 1e30, position: { x: 1e11, y: 0, z: 0 }, velocity: { x: 0, y: 30000, z: 0 } },
    ],
    timeStepSeconds: 43200,
    gravitationalConstant: 6.6743e-11,
    softeningLengthMeters: 1e8,
    maxSteps: 5000,
    targetSimulationTimeSeconds: null,
  }
}

describe('mockRepository 历史与回放（F7）', () => {
  beforeEach(() => {
    resetRepository()
    resetSchedulerState()
  })
  afterEach(() => {
    stopReplayJobs()
    stopMockScheduler()
  })

  it('getHistorySlice 返回闭区间内升序点并支持抽样', () => {
    const record = createRecord(baseConfig(), '历史实验')
    advance(record, 100)
    const slice = getHistorySlice(record, 10, 20, 2000)
    expect(slice.points.length).toBe(11)
    expect(slice.points[0].step).toBe(10)
    expect(slice.points[10].step).toBe(20)
    expect(slice.points.every((p, i) => i === 0 || p.step > slice.points[i - 1].step)).toBe(true)
    expect(slice.downsampled).toBe(false)
    expect(slice.availableFromStep).toBe(10)
    expect(slice.availableToStep).toBe(20)
    expect(slice.currentState?.step).toBe(100)

    const sampled = getHistorySlice(record, 0, 100, 5)
    expect(sampled.downsampled).toBe(true)
    expect(sampled.points.length).toBe(5)
    expect(sampled.points[0].step).toBe(0)
    expect(sampled.points[sampled.points.length - 1].step).toBe(100)
  })

  it('upsertEvent 按 eventId 原位替换并保持创建顺序', () => {
    const record = createRecord(baseConfig(), '事件实验')
    const first = emitEvent(record, 'NEAR_ENCOUNTER', '进入近遇', {
      eventId: 'aaaaaaaa-0000-4000-8000-000000000001',
      phase: 'ENTER',
    })
    emitEvent(record, 'NEAR_ENCOUNTER', '更新近遇', {
      eventId: 'aaaaaaaa-0000-4000-8000-000000000001',
      phase: 'UPDATE',
    })
    expect(record.events.length).toBe(1)
    expect(record.events[0].phase).toBe('UPDATE')
    expect(record.events[0].sequence).toBe(first.sequence)
  })

  it('upsertEvent 对新 eventId 追加事件', () => {
    const record = createRecord(baseConfig(), '事件实验')
    upsertEvent(record, {
      sequence: 1,
      eventId: null,
      type: 'STATUS_CHANGE',
      step: 0,
      simulationTimeSeconds: 0,
      timestamp: new Date().toISOString(),
      message: '完成',
    })
    expect(record.events.length).toBe(1)
    expect(record.events[0].type).toBe('STATUS_CHANGE')
  })

  it('createReplayJob：当前步返回 CURRENT_STATE 200，归档精确点返回 ARCHIVE_EXACT 200', () => {
    const record = createRecord(baseConfig(), '回放实验')
    advance(record, 50)

    const current = createReplayJob(record, record.state.step)
    expect(current.httpStatus).toBe(200)
    expect(current.job.status).toBe('COMPLETED')
    expect(current.job.source).toBe('CURRENT_STATE')
    expect(current.job.result?.step).toBe(50)

    const exact = createReplayJob(record, 10)
    expect(exact.httpStatus).toBe(200)
    expect(exact.job.status).toBe('COMPLETED')
    expect(exact.job.source).toBe('ARCHIVE_EXACT')
    expect(exact.job.result?.step).toBe(10)
  })

  it('createReplayJob：归档外目标进入 RECOMPUTED 队列并返回 202', () => {
    const record = createRecord(baseConfig(), '回放实验')
    // 只采样到步 0，目标 25 不在归档内，需要重算。
    const job = createReplayJob(record, 25)
    expect(job.httpStatus).toBe(202)
    expect(job.job.status).toBe('QUEUED')
    expect(job.job.source).toBe('RECOMPUTED')
    expect(job.job.baseStep).toBe(0)
    expect(job.job.totalSteps).toBe(25)

    const fetched = getReplayJob(record, job.job.jobId)
    expect(fetched?.jobId).toBe(job.job.jobId)
    expect(replayJobResponse(fetched!).targetStep).toBe(25)
  })

  it('pendingReplayJobCount 只统计 QUEUED/RUNNING 任务', () => {
    const record = createRecord(baseConfig(), '回放实验')
    const completed = createReplayJob(record, record.state.step)
    const queued = createReplayJob(record, 30)
    expect(completed.job.status).toBe('COMPLETED')
    expect(queued.job.status).toBe('QUEUED')
    expect(pendingReplayJobCount()).toBe(1)
  })

  it('deleteReplayJob：运行中任务进入 CANCELLED，终态任务保持原终态', () => {
    const record = createRecord(baseConfig(), '回放实验')
    const queued = createReplayJob(record, 30)
    const completed = createReplayJob(record, record.state.step)

    expect(deleteReplayJob(record, queued.job.jobId)).toBe(true)
    expect(deleteReplayJob(record, completed.job.jobId)).toBe(true)
    expect(getReplayJob(record, queued.job.jobId)?.status).toBe('CANCELLED')
    expect(getReplayJob(record, completed.job.jobId)?.status).toBe('COMPLETED')
    expect(deleteReplayJob(record, '00000000-0000-0000-0000-000000000000')).toBe(false)
  })

  it('advance 会记录采样点供历史查询使用', () => {
    const record = createRecord(baseConfig(), '采样实验')
    advance(record, 5)
    const slice = getHistorySlice(record, 0, 5, 100)
    expect(slice.points.length).toBe(6)
    expect(slice.currentState?.step).toBe(5)
    expect(getRecord(record.id)).toBeDefined()
  })
})