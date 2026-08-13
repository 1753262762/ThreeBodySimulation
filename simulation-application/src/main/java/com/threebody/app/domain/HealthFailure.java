package com.threebody.app.domain;

public record HealthFailure(
        String code,
        String bodyId,
        String field,
        long step,
        double simulationTimeSeconds,
        String value,
        String message) {
}
