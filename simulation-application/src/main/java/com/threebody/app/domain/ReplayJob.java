package com.threebody.app.domain;

import com.threebody.core.SimulationState;
import java.time.Instant;

/**
 * 精确回放任务。
 *
 * @param jobId           任务 ID
 * @param experimentId    实验 ID
 * @param targetStep      目标步
 * @param status          状态
 * @param source          结果来源；终态前为 null
 * @param baseStep        重算起点步；精确命中时为 null
 * @param completedSteps  已完成的积分步
 * @param totalSteps      总需积分步
 * @param progress        进度 0..1，完成时固定 1
 * @param result          结果状态；完成时为非 null
 * @param error           失败原因；否则为 null
 * @param createdAt       创建时间
 * @param updatedAt       最近更新时间
 * @param expiresAt       完成/取消结果的保留截止时间
 */
public record ReplayJob(
        String jobId,
        String experimentId,
        long targetStep,
        ReplayJobStatus status,
        ReplaySource source,
        Long baseStep,
        long completedSteps,
        long totalSteps,
        double progress,
        SimulationState result,
        String error,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt) {
}
