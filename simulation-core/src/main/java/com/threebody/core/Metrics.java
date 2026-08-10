package com.threebody.core;

import java.util.List;

/**
 * 守恒量与诊断指标。能量单位 J，角动量单位 kg*m^2/s，动量单位 kg*m/s，距离单位 m。
 *
 * @param kineticEnergyJoules       动能(J)
 * @param potentialEnergyJoules     势能(J)，使用软化势 -G*m1*m2/sqrt(r^2+eps^2)
 * @param totalEnergyJoules         总能量(J)
 * @param initialTotalEnergyJoules  初始总能量(J)
 * @param relativeEnergyDrift       相对能量漂移
 * @param angularMomentum           总角动量向量
 * @param linearMomentum            总动量向量
 * @param minimumPairDistanceMeters 当前最近两体距离(m)
 * @param minimumPairBodyIds        最近两体标识
 */
public record Metrics(
        double kineticEnergyJoules,
        double potentialEnergyJoules,
        double totalEnergyJoules,
        double initialTotalEnergyJoules,
        double relativeEnergyDrift,
        Vector3 angularMomentum,
        Vector3 linearMomentum,
        double minimumPairDistanceMeters,
        List<String> minimumPairBodyIds) {

    public Metrics {
        minimumPairBodyIds = minimumPairBodyIds == null ? List.of() : List.copyOf(minimumPairBodyIds);
    }

    public double angularMomentumMagnitude() {
        return angularMomentum.length();
    }

    public double linearMomentumMagnitude() {
        return linearMomentum.length();
    }
}