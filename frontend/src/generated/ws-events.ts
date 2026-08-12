/* eslint-disable */
/**
 * 本文件由 scripts/generate-contracts.mjs 自动生成，请勿手工修改。
 * 契约来源：contracts/openapi.yaml、contracts/ws-events.schema.json
 */
/**
 * WebSocket 地址为 /ws/v1/experiments/{id}。所有数值为 SI 单位。实时发布上限为快照 60 Hz、轨迹增量 60 Hz、指标 2 Hz（按单调墙钟截止时间，错过周期不补发）。envelope 的 sequence 在单个实验内单调递增，客户端必须丢弃不大于已处理值的消息；它只负责消息传输去重与顺序，不等于事件序列号。NEAR_ENCOUNTER 的 ENTER/FINAL 与 DIAGNOSTIC 走可靠 FIFO，NEAR_ENCOUNTER UPDATE 按 eventId 最新值合并，不占用可靠队列。1.1 版本 NEAR_ENCOUNTER 与 DIAGNOSTIC 的 payload 均为 { event: SimulationEvent }。
 */
export type ThreeBodyLabWebSocketEnvelope = {
  [k: string]: unknown
} & {
  schemaVersion: '1.1'
  type: 'SNAPSHOT' | 'TRAJECTORY' | 'METRICS' | 'STATUS' | 'NEAR_ENCOUNTER' | 'DIAGNOSTIC' | 'ERROR'
  experimentId: string
  sequence: number
  timestamp: string
  payload: {}
}
