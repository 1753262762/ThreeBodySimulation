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
        if (maxSteps != null && maxSteps > 0) {
            remaining = Math.max(0L, maxSteps - step);
            ratio = Math.min(1.0, (double) step / maxSteps);
        }
        if (targetTime != null && targetTime > 0) {
            long timeRemaining = (long) Math.ceil(
                    Math.max(0.0, targetTime - simTime) / config.timeStepSeconds());
            remaining = remaining == null ? timeRemaining : Math.min(remaining, timeRemaining);
            double timeRatio = Math.min(1.0, simTime / targetTime);
            ratio = ratio == null ? timeRatio : Math.max(ratio, timeRatio);
        }
        return new Progress(step, simTime, maxSteps, targetTime, ratio, stepsPerSecond, remaining);
    }
}
