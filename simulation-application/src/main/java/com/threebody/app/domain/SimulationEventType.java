package com.threebody.app.domain;

/**
 * 实验事件类型，与 OpenAPI SimulationEvent.type 保持一致。
 * NUMERICAL_WARNING 为读取兼容保留枚举值，生产端不再新发，由 DIAGNOSTIC 取代。
 */
public enum SimulationEventType {
    NEAR_ENCOUNTER,
    STATUS_CHANGE,
    DIAGNOSTIC,
    NUMERICAL_WARNING,
    ERROR
}