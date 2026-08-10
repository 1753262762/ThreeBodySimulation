package com.threebody.app.domain;

import java.time.Instant;
import java.util.List;

/**
 * 实验生命周期中记录的事件。
 *
 * @param sequence               实验内事件序号（从 1 开始）
 * @param type                   事件类型
 * @param step                   事件发生时已完成步数
 * @param simulationTimeSeconds  模拟时间(s)
 * @param timestamp              服务器时间
 * @param message                中文描述
 * @param bodyIds                相关天体 ID，可为 null
 * @param distanceMeters         近距离事件距离(m)，非近距离事件为 null
 */
public record SimulationEvent(
        long sequence,
        SimulationEventType type,
        long step,
        double simulationTimeSeconds,
        Instant timestamp,
        String message,
        List<String> bodyIds,
        Double distanceMeters) {

    public SimulationEvent {
        bodyIds = bodyIds == null ? List.of() : List.copyOf(bodyIds);
    }
}