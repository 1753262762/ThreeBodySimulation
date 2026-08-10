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
 */
public record ExperimentMessage(
        ExperimentMessageType type,
        String experimentId,
        long sequence,
        Instant timestamp,
        Object payload) {
}