package com.threebody.app.domain;

import java.time.Instant;

/**
 * 列表使用的实验摘要，与 OpenAPI ExperimentSummary 对应。
 */
public record ExperimentSummaryView(
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
        SimulationHealthStatus healthStatus,
        ExperimentLineage lineage) {

    public static ExperimentSummaryView from(Experiment e, int queuePosition, long storageBytes) {
        return new ExperimentSummaryView(
                e.id(), e.name(), e.status(), queuePosition,
                e.createdAt(), e.updatedAt(), e.startedAt(), e.completedAt(),
                e.config().bodyCount(), e.progress(), e.endReason(), storageBytes, e.errorMessage(),
                e.healthReport() != null ? e.healthReport().status() : null,
                e.lineage());
    }
}
