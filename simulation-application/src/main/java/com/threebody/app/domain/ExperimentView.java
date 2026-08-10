package com.threebody.app.domain;

import com.threebody.core.SimulationConfig;
import com.threebody.core.SimulationState;
import java.time.Instant;
import java.util.List;

/**
 * 实验详情，与 OpenAPI Experiment 对应：摘要字段 + 完整状态。
 */
public record ExperimentView(
        String id,
        String name,
        ExperimentStatus status,
        int queuePosition,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt,
        int bodyCount,
        Progress progress,
        EndReason endReason,
        long storageBytes,
        String errorCode,
        SimulationConfig config,
        SimulationState state,
        ExperimentMetrics metrics,
        TrajectoryInfo trajectory,
        List<SimulationEvent> events,
        long lastSequence,
        String errorMessage) {

    public ExperimentView {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public static ExperimentView from(Experiment e, int queuePosition, long storageBytes) {
        return new ExperimentView(
                e.id(), e.name(), e.status(), queuePosition,
                e.createdAt(), e.updatedAt(), e.startedAt(), e.completedAt(),
                e.config().bodyCount(), e.progress(), e.endReason(), storageBytes, e.errorMessage(),
                e.config(), e.state(), e.metrics(), e.trajectoryInfo(), e.events(), e.lastSequence(), e.errorMessage());
    }
}