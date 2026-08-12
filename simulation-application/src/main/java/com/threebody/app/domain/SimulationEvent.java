package com.threebody.app.domain;

import java.time.Instant;
import java.util.List;

/**
 * 实验生命周期中记录的事件。
 *
 * @param sequence               逻辑事件创建顺序，创建后不变；同一 eventId 的 UPDATE/FINAL 不重新分配
 * @param eventId                稳定事件标识，新事件必填；旧 1.0 事件可为空
 * @param type                   事件类型
 * @param phase                  近遇生命周期阶段；诊断事件固定 FINAL；状态/错误事件为 null
 * @param step                   当前修订对应的步
 * @param simulationTimeSeconds  模拟时间(s)
 * @param timestamp              服务器时间
 * @param message                中文描述
 * @param bodyIds                相关天体 ID，可为 null
 * @param distanceMeters         兼容字段，优先等于 closestDistanceMeters
 * @param thresholdMeters        近遇阈值(m)，非近遇事件为 null
 * @param triggerDistanceMeters  触发近遇时的距离(m)
 * @param closestDistanceMeters  事件区间内真实最近距离(m)
 * @param closestStep            事件区间内真实最近点对应步
 * @param closestSimulationTimeSeconds 事件区间内真实最近点对应模拟时间(s)
 * @param midpointPosition       真实最近点两体中点位置(m)
 * @param diagnostic             结构化诊断，非诊断事件为 null
 */
public record SimulationEvent(
        long sequence,
        String eventId,
        SimulationEventType type,
        EventPhase phase,
        long step,
        double simulationTimeSeconds,
        Instant timestamp,
        String message,
        List<String> bodyIds,
        Double distanceMeters,
        Double thresholdMeters,
        Double triggerDistanceMeters,
        Double closestDistanceMeters,
        Long closestStep,
        Double closestSimulationTimeSeconds,
        com.threebody.core.Vector3 midpointPosition,
        Diagnostic diagnostic) {

    public SimulationEvent {
        bodyIds = bodyIds == null ? List.of() : List.copyOf(bodyIds);
    }

    /** 简单状态/错误事件工厂，新增可空字段全部为 null。 */
    public static SimulationEvent simple(long sequence, SimulationEventType type,
            long step, double simulationTimeSeconds, Instant timestamp, String message) {
        return new SimulationEvent(sequence, null, type, null, step, simulationTimeSeconds,
                timestamp, message, null, null, null, null, null, null, null, null, null);
    }
}
