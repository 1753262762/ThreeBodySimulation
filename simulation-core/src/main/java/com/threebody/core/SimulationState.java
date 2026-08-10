package com.threebody.core;

import java.util.List;

/**
 * 某一步的完整模拟状态。
 *
 * @param step                  已完成步数
 * @param simulationTimeSeconds 模拟时间(s)
 * @param bodies                各天体状态
 */
public record SimulationState(long step, double simulationTimeSeconds, List<BodyState> bodies) {

    public SimulationState {
        bodies = bodies == null ? List.of() : List.copyOf(bodies);
    }
}