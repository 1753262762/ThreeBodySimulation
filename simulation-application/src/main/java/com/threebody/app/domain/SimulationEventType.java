package com.threebody.app.domain;

/** 实验事件类型，与 OpenAPI SimulationEvent.type 保持一致。 */
public enum SimulationEventType {
    NEAR_ENCOUNTER,
    STATUS_CHANGE,
    NUMERICAL_WARNING,
    ERROR
}