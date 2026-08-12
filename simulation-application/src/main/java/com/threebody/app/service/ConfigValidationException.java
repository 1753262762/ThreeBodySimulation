package com.threebody.app.service;

import com.threebody.core.ValidationIssue;
import java.util.List;

/**
 * 配置校验失败的应用异常，携带完整问题列表。
 * Web 层将其映射为 400 VALIDATION_FAILED 并返回 issues。
 */
public class ConfigValidationException extends RuntimeException {

    private final List<ValidationIssue> issues;

    public ConfigValidationException(List<ValidationIssue> issues) {
        super("配置校验失败");
        this.issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public List<ValidationIssue> issues() {
        return issues;
    }
}
