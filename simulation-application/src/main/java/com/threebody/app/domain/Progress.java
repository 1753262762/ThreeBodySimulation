package com.threebody.app.domain;

/**
 * 进度信息。步数与时间均为 SI 单位；速度指标可为 null（尚未运行）。
 */
public record Progress(
        long step,
        double simulationTimeSeconds,
        Long maxSteps,
        Double targetSimulationTimeSeconds,
        Double completionRatio,
        Double stepsPerSecond,
        Long estimatedRemainingSteps) {

    public static Progress of(com.threebody.core.SimulationConfig config, long step, double simTime,
            Double stepsPerSecond) {
        Long maxSteps = config.maxSteps();
        Double targetTime = config.targetSimulationTimeSeconds();
        Double ratio = null;
        Long remaining = null;
        if (maxSteps != null) {
            remaining = Math.max(0L, maxSteps - step);
            ratio = maxSteps > 0 ? Math.min(1.0, (double) step / maxSteps) : null;
        } else if (targetTime != null && targetTime > 0) {
            remaining = (long) Math.ceil(Math.max(0.0, targetTime - simTime) / config.timeStepSeconds());
            ratio = Math.min(1.0, simTime / targetTime);
        }
        return new Progress(step, simTime, maxSteps, targetTime, ratio, stepsPerSecond, remaining);
    }
}