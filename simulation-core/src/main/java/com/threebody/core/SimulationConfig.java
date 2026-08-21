package com.threebody.core;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * 模拟配置。全部为 SI 单位；maxSteps 与 targetSimulationTimeSeconds 至少提供一个作为结束条件。
 *
 * @param name                        配置名称
 * @param bodies                      2-100 个天体
 * @param timeStepSeconds             时间步长(s)
 * @param gravitationalConstant       引力常数(m^3 kg^-1 s^-2)
 * @param softeningLengthMeters       软化长度 epsilon(m)
 * @param maxSteps                    最大步数结束条件，可为 null
 * @param targetSimulationTimeSeconds 目标模拟时间结束条件(s)，可为 null
 */
public record SimulationConfig(
        String name,
        List<BodySpec> bodies,
        double timeStepSeconds,
        double gravitationalConstant,
        double softeningLengthMeters,
        Long maxSteps,
        Double targetSimulationTimeSeconds) {

    public SimulationConfig {
        bodies = bodies == null ? List.of() : List.copyOf(bodies);
    }

    public int bodyCount() {
        return bodies.size();
    }

    public OptionalLong maxStepsValue() {
        return maxSteps == null ? OptionalLong.empty() : OptionalLong.of(maxSteps);
    }

    public OptionalDouble targetTimeValue() {
        return targetSimulationTimeSeconds == null
                ? OptionalDouble.empty()
                : OptionalDouble.of(targetSimulationTimeSeconds);
    }

    /** 近距离事件阈值(m)。 */
    public double nearEncounterThresholdMeters() {
        return PhysicalConstants.NEAR_ENCOUNTER_SOFTENING_FACTOR * softeningLengthMeters;
    }

    public SimulationConfig withBodies(List<BodySpec> newBodies) {
        return new SimulationConfig(name, newBodies, timeStepSeconds, gravitationalConstant,
                softeningLengthMeters, maxSteps, targetSimulationTimeSeconds);
    }

    public SimulationConfig withName(String newName) {
        return new SimulationConfig(newName, bodies, timeStepSeconds, gravitationalConstant,
                softeningLengthMeters, maxSteps, targetSimulationTimeSeconds);
    }

    /**
     * 估算总步数：取两个结束条件中先达到者。
     * maxCandidate 为 maxSteps（缺失时为 Long.MAX_VALUE）；
     * targetCandidate 为 ceil(targetTime / dt)（缺失时为 Long.MAX_VALUE）；
     * 结果为两者较小值。两者都缺失时返回 null，由强制校验拦截。
     */
    public Long estimatedTotalSteps() {
        long maxCandidate = maxSteps != null ? maxSteps : Long.MAX_VALUE;
        long targetCandidate = Long.MAX_VALUE;
        if (targetSimulationTimeSeconds != null && timeStepSeconds > 0.0) {
            double estimate = targetSimulationTimeSeconds / timeStepSeconds;
            targetCandidate = estimate >= Long.MAX_VALUE ? Long.MAX_VALUE
                    : Math.max(0L, (long) Math.ceil(estimate));
        }
        if (maxCandidate == Long.MAX_VALUE && targetCandidate == Long.MAX_VALUE) {
            return null;
        }
        return Math.min(maxCandidate, targetCandidate);
    }
}
