package com.threebody.app.domain;

import java.util.List;

/**
 * 对照实验来源快照。来源删除后仍保留名称、配置差异和原始 Health，且不级联删除后代。
 */
public record ExperimentLineage(
        String sourceExperimentId,
        String sourceExperimentName,
        String rootExperimentId,
        int retryDepth,
        String recommendationCode,
        RetryStrategy strategy,
        Double beforeTimeStepSeconds,
        Double afterTimeStepSeconds,
        Long beforeMaxSteps,
        Long afterMaxSteps,
        Double beforeTargetSimulationTimeSeconds,
        Double afterTargetSimulationTimeSeconds,
        Double beforeEstimatedSimulationTimeSeconds,
        Double afterEstimatedSimulationTimeSeconds,
        Long beforeEstimatedSteps,
        Long afterEstimatedSteps,
        List<String> changedFields,
        SimulationHealthStatus sourceHealthStatus) {

    public ExperimentLineage {
        changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
    }
}
