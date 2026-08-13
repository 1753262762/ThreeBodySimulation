package com.threebody.app.domain;

/** Run 级数值健康状态，不描述轨道或物理系统是否稳定。 */
public enum SimulationHealthStatus {
    GOOD,
    WARNING,
    POOR,
    FAILED
}
