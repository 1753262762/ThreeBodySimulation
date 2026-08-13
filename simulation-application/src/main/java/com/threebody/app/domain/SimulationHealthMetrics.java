package com.threebody.app.domain;

public record SimulationHealthMetrics(
        double currentEnergyDrift,
        double peakEnergyDrift,
        long peakEnergyDriftStep,
        double peakEnergyDriftSimulationTimeSeconds,
        Double currentAngularMomentumDrift,
        Double peakAngularMomentumDrift,
        Long peakAngularMomentumDriftStep,
        Double peakAngularMomentumDriftSimulationTimeSeconds,
        DriftTrend energyTrend,
        DriftTrend angularMomentumTrend,
        Double closestApproachMeters,
        long closeEncounterCount,
        HealthCloseEncounter latestCloseEncounter) {
}
