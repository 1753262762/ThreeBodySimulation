/**
 * Mock WebSocket 服务器。
 *
 * 使用 MSW 的 WebSocket 拦截能力，按契约信封广播消息。
 * 返回的 handler 必须传给 setupWorker(...handlers)，否则不会拦截连接。
 */
import { ws } from 'msw/core/ws'
import { registerMockClient, unregisterMockClient, startMockScheduler } from './mockScheduler'
import { getRecord, toExperiment } from './mockRepository'

export function createMockWsHandler() {
  const link = ws.link('*/ws/v1/experiments/*')
  return link.addEventListener('connection', ({ client }) => {
    const path = client.url.pathname
    const id = path.split('/').pop() ?? ''
    const record = getRecord(id)
    registerMockClient(id, { id, send: (message) => client.send(message) })
    client.addEventListener('close', () => unregisterMockClient(id))
    if (!record) {
      client.send(
        JSON.stringify({
          schemaVersion: '1.0',
          type: 'ERROR',
          experimentId: id,
          sequence: 1,
          timestamp: new Date().toISOString(),
          payload: { code: 'EXPERIMENT_NOT_FOUND', message: '实验不存在。', recoverable: false },
        }),
      )
      return
    }
    const experiment = toExperiment(record)
    // 连接问候使用递增序列号，确保大于 REST 返回的 lastSequence 而不被客户端丢弃。
    record.wsSequence += 1
    const greetingSeq = record.wsSequence
    client.send(
      JSON.stringify({
        schemaVersion: '1.0',
        type: 'STATUS',
        experimentId: experiment.id,
        sequence: greetingSeq,
        timestamp: new Date().toISOString(),
        payload: {
          status: experiment.status,
          previousStatus: null,
          step: experiment.progress.step,
          simulationTimeSeconds: experiment.progress.simulationTimeSeconds,
          endReason: experiment.endReason,
          completionRatio: experiment.progress.completionRatio,
          queuePosition: 0,
          message: '连接建立。',
        },
      }),
    )
    if (experiment.state) {
      record.wsSequence += 1
      client.send(
        JSON.stringify({
          schemaVersion: '1.0',
          type: 'SNAPSHOT',
          experimentId: experiment.id,
          sequence: record.wsSequence,
          timestamp: new Date().toISOString(),
          payload: {
            step: experiment.state.step,
            simulationTimeSeconds: experiment.state.simulationTimeSeconds,
            bodies: experiment.state.bodies,
          },
        }),
      )
    }
    startMockScheduler()
  })
}
