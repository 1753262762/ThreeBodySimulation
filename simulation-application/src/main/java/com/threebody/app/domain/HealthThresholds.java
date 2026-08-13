package com.threebody.app.domain;

/** 项目默认数值容差，不代表普适物理真理。 */
public record HealthThresholds(
        double energyWarning,
        double energyPoor,
        double angularMomentumWarning,
        double angularMomentumPoor) {

    public static HealthThresholds defaults() {
        return new HealthThresholds(1e-3, 1e-2, 1e-3, 1e-2);
    }
}
