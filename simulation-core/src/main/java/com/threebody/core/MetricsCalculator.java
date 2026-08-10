package com.threebody.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 计算能量、动量、角动量与最近距离。势能与积分器使用同一软化核，
 * 保证能量守恒判断与实际动力学一致。
 */
public final class MetricsCalculator {

    private MetricsCalculator() {
    }

    /**
     * 计算总能量(J)：动能加软化势能。
     */
    public static double totalEnergy(SimulationConfig config, SimulationState state) {
        return kineticEnergy(config, state) + potentialEnergy(config, state);
    }

    public static double kineticEnergy(SimulationConfig config, SimulationState state) {
        List<BodySpec> specs = config.bodies();
        List<BodyState> bodies = state.bodies();
        double sum = 0.0;
        for (int i = 0; i < bodies.size(); i++) {
            sum += 0.5 * specs.get(i).massKg() * bodies.get(i).velocity().squaredLength();
        }
        return sum;
    }

    public static double potentialEnergy(SimulationConfig config, SimulationState state) {
        List<BodySpec> specs = config.bodies();
        List<BodyState> bodies = state.bodies();
        double g = config.gravitationalConstant();
        double eps2 = config.softeningLengthMeters() * config.softeningLengthMeters();
        double sum = 0.0;
        for (int i = 0; i < bodies.size(); i++) {
            for (int j = i + 1; j < bodies.size(); j++) {
                double r2 = bodies.get(j).position().subtract(bodies.get(i).position()).squaredLength() + eps2;
                if (r2 <= 0.0) {
                    continue;
                }
                sum -= g * specs.get(i).massKg() * specs.get(j).massKg() / Math.sqrt(r2);
            }
        }
        return sum;
    }

    public static Vector3 linearMomentum(SimulationConfig config, SimulationState state) {
        List<BodySpec> specs = config.bodies();
        List<BodyState> bodies = state.bodies();
        Vector3 sum = Vector3.ZERO;
        for (int i = 0; i < bodies.size(); i++) {
            sum = sum.add(bodies.get(i).velocity().multiply(specs.get(i).massKg()));
        }
        return sum;
    }

    public static Vector3 angularMomentum(SimulationConfig config, SimulationState state) {
        List<BodySpec> specs = config.bodies();
        List<BodyState> bodies = state.bodies();
        Vector3 sum = Vector3.ZERO;
        for (int i = 0; i < bodies.size(); i++) {
            BodyState b = bodies.get(i);
            sum = sum.add(b.position().cross(b.velocity().multiply(specs.get(i).massKg())));
        }
        return sum;
    }

    /**
     * 相对能量漂移：(E - E0)/|E0|；E0 为 0 时退化为绝对漂移。
     */
    public static double relativeEnergyDrift(double initialEnergy, double currentEnergy) {
        if (initialEnergy == 0.0 || !Double.isFinite(initialEnergy)) {
            return currentEnergy - initialEnergy;
        }
        return (currentEnergy - initialEnergy) / Math.abs(initialEnergy);
    }

    /**
     * 计算完整指标集合。
     *
     * @param initialTotalEnergy 初始总能量(J)，用于漂移计算
     */
    public static Metrics compute(SimulationConfig config, SimulationState state, double initialTotalEnergy) {
        double kinetic = kineticEnergy(config, state);
        double potential = potentialEnergy(config, state);
        double total = kinetic + potential;

        double minDistance = Double.POSITIVE_INFINITY;
        List<String> minPair = new ArrayList<>();
        List<BodyState> bodies = state.bodies();
        for (int i = 0; i < bodies.size(); i++) {
            for (int j = i + 1; j < bodies.size(); j++) {
                double distance = bodies.get(j).position().subtract(bodies.get(i).position()).length();
                if (distance < minDistance) {
                    minDistance = distance;
                    minPair = List.of(bodies.get(i).id(), bodies.get(j).id());
                }
            }
        }
        if (!Double.isFinite(minDistance)) {
            minDistance = 0.0;
            minPair = List.of();
        }

        return new Metrics(
                kinetic,
                potential,
                total,
                initialTotalEnergy,
                relativeEnergyDrift(initialTotalEnergy, total),
                angularMomentum(config, state),
                linearMomentum(config, state),
                minDistance,
                minPair);
    }
}