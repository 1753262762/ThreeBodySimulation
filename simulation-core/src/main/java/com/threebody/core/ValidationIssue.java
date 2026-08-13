package com.threebody.core;

/**
 * 单条校验问题。
 *
 * @param field    字段路径，例如 bodies[2].massKg
 * @param code     问题编码
 * @param message  中文描述
 * @param severity 级别
 * @param riskLevel 风险级别（CAUTION/HIGH），仅 WARNING 填写；ERROR 为 null
 */
public record ValidationIssue(
        String field,
        ValidationCode code,
        String message,
        ValidationSeverity severity,
        RiskLevel riskLevel,
        ValidationGuidance guidance) {

    public ValidationIssue(String field, ValidationCode code, String message,
            ValidationSeverity severity, RiskLevel riskLevel) {
        this(field, code, message, severity, riskLevel, null);
    }

    public static ValidationIssue error(String field, ValidationCode code, String message) {
        return new ValidationIssue(field, code, message, ValidationSeverity.ERROR, null, null);
    }

    public static ValidationIssue warning(String field, ValidationCode code, String message) {
        return new ValidationIssue(field, code, message, ValidationSeverity.WARNING, null, null);
    }

    /** WARNING 风险问题；调用方必须给出明确风险级别。 */
    public static ValidationIssue risk(String field, ValidationCode code, String message,
            RiskLevel riskLevel) {
        return new ValidationIssue(field, code, message, ValidationSeverity.WARNING, riskLevel, null);
    }

    public static ValidationIssue risk(String field, ValidationCode code, String message,
            RiskLevel riskLevel, ValidationGuidance guidance) {
        return new ValidationIssue(field, code, message, ValidationSeverity.WARNING, riskLevel, guidance);
    }
}
