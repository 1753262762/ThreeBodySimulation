package com.threebody.core;

import java.util.List;

/** 合法配置中可以直接计算出的运行事实，全部使用 SI 单位。 */
public record ConfigSummary(
        Long estimatedSteps,
        Double estimatedSimulationTimeSeconds,
        LimitingEndCondition limitingEndCondition,
        Double initialMinimumPairDistanceMeters,
        List<String> initialMinimumPairBodyIds,
        Double softeningToInitialDistanceRatio) {

    public ConfigSummary {
        initialMinimumPairBodyIds = initialMinimumPairBodyIds == null
                ? List.of()
                : List.copyOf(initialMinimumPairBodyIds);
    }
}
