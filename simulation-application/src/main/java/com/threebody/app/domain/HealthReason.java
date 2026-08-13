package com.threebody.app.domain;

public record HealthReason(
        String code,
        HealthReasonSeverity severity,
        String metric,
        String message) {
}
