package com.threebody.app.event;

import java.time.Instant;

/**
 * 应用层对外发布的实验消息。web 层负责包一层 schemaVersion 后发送给 WebSocket 客户端。
 *
 * @param type          消息类型
 * @param experimentId  实验 ID
 * @param sequence      实验内单调递增序号，从 1 开始
 * @param timestamp     服务器时间
 * @param payload       类型化载荷
 * @param mergeKey      最新值合并键：非 null 的消息按该键最新值覆盖（如 UPDATE 按 eventId）；
 *                      null 表示可靠有序消息，进入可靠 FIFO
 */
public record ExperimentMessage(
        ExperimentMessageType type,
        String experimentId,
        long sequence,
        Instant timestamp,
        Object payload,
        String mergeKey) {

    public ExperimentMessage {
        // 兼容旧构造签名：mergeKey 为空时按 SNAPSHOT/TRAJECTORY/METRICS 的既有最新值语义
        mergeKey = mergeKey != null ? mergeKey
                : switch (type) {
                    case SNAPSHOT, TRAJECTORY, METRICS, HEALTH -> type.name();
                    default -> null;
                };
    }

    public ExperimentMessage(ExperimentMessageType type, String experimentId, long sequence,
            Instant timestamp, Object payload) {
        this(type, experimentId, sequence, timestamp, payload, null);
    }
}
