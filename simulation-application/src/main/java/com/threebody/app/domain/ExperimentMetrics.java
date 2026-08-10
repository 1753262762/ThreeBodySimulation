package com.threebody.app.domain;

import com.threebody.core.Vector3;
import java.util.List;

/**
 * 指标快照，字段与 OpenAPI Metrics 对应；单位均为 SI。
 */
public record ExperimentMetrics(
        double kineticEnergyJoules,
        double potentialEnergyJoules,
        double totalEnergyJoules,
        double initialTotalEnergyJoules,
        double relativeEnergyDrift,
        Vector3 angularMomentum,
        double angularMomentumMagnitude,
        Vector3 linearMomentum,
        double linearMomentumMagnitude,
        double minimumPairDistanceMeters,
        List<String> minimumPairBodyIds,
        Double allTimeMinimumPairDistanceMeters,
        Long allTimeMinimumPairDistanceStep,
        Double stepsPerSecond,
        Double elapsedWallClockSeconds) {

    public ExperimentMetrics {
        minimumPairBodyIds = minimumPairBodyIds == null ? List.of() : List.copyOf(minimumPairBodyIds);
    }
}