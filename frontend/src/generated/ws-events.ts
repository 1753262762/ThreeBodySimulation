/* eslint-disable */
/**
 * 本文件由 scripts/generate-contracts.mjs 自动生成，请勿手工修改。
 * 契约来源：contracts/openapi.yaml、contracts/ws-events.schema.json
 */
/**
 * WebSocket 地址为 /ws/v1/experiments/{id}。所有数值为 SI 单位。快照最高 30 Hz、轨迹增量 10 Hz、指标 2 Hz。sequence 在单个实验内单调递增，客户端必须丢弃不大于已处理值的消息。
 */
export type ThreeBodyLabWebSocketEnvelope = {
  [k: string]: unknown
} & {
  schemaVersion: '1.0'
  type: 'SNAPSHOT' | 'TRAJECTORY' | 'METRICS' | 'STATUS' | 'NEAR_ENCOUNTER' | 'ERROR'
  experimentId: string
  sequence: number
  timestamp: string
  payload: {}
}
