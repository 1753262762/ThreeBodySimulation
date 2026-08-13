package com.threebody.app.domain;

import java.util.List;

public record SimulationHealthReport(
        SimulationHealthStatus status,
        SimulationHealthMetrics metrics,
        HealthThresholds thresholds,
        List<HealthReason> reasons,
        List<HealthRecommendation> recommendations,
        HealthFailure failure,
        long analyzedStep,
        double analyzedSimulationTimeSeconds,
        long sampleStride,
        long sampleCount) {

    public SimulationHealthReport {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
    }
}
