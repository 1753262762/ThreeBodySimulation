package com.threebody.core;

/**
 * 单条校验问题。
 *
 * @param field    字段路径，例如 bodies[2].massKg
 * @param code     问题编码
 * @param message  中文描述
 * @param severity 级别
 */
public record ValidationIssue(
        String field,
        ValidationCode code,
        String message,
        ValidationSeverity severity) {

    public static ValidationIssue error(String field, ValidationCode code, String message) {
        return new ValidationIssue(field, code, message, ValidationSeverity.ERROR);
    }

    public static ValidationIssue warning(String field, ValidationCode code, String message) {
        return new ValidationIssue(field, code, message, ValidationSeverity.WARNING);
    }
}