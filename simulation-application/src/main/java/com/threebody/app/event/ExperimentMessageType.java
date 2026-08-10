package com.threebody.app.event;

/** WebSocket 消息类型，与 ws-events.schema.json 的 type 枚举一致。 */
public enum ExperimentMessageType {
    SNAPSHOT,
    TRAJECTORY,
    METRICS,
    STATUS,
    NEAR_ENCOUNTER,
    ERROR
}