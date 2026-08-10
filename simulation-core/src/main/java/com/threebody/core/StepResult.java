package com.threebody.core;

import java.util.List;

/**
 * 单步推进结果。
 *
 * @param state           推进后的状态
 * @param nearEncounters  本步触发的近距离事件
 */
public record StepResult(SimulationState state, List<NearEncounter> nearEncounters) {

    public StepResult {
        nearEncounters = nearEncounters == null ? List.of() : List.copyOf(nearEncounters);
    }

    public boolean hasNearEncounter() {
        return !nearEncounters.isEmpty();
    }
}