package com.threebody.app.domain;

public record HealthRecommendation(
        String code,
        HealthRecommendationAction action,
        String message,
        HealthConfigPatch configPatch,
        String rationale,
        String tradeoff,
        String verification) {

    public HealthRecommendation(String code, HealthRecommendationAction action, String message,
            HealthConfigPatch configPatch) {
        this(code, action, message, configPatch, null, null, null);
    }
}
