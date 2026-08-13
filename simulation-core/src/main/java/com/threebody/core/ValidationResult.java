package com.threebody.core;

import java.util.List;

/**
 * 校验结果。
 *
 * @param issues           全部问题
 * @param normalizedConfig 合法时返回补齐 ID 与颜色后的配置，否则为 null
 * @param estimatedSteps   预计总步数，可为 null
 */
public record ValidationResult(
        List<ValidationIssue> issues,
        SimulationConfig normalizedConfig,
        Long estimatedSteps,
        ConfigSummary configSummary) {

    public ValidationResult(List<ValidationIssue> issues, SimulationConfig normalizedConfig,
            Long estimatedSteps) {
        this(issues, normalizedConfig, estimatedSteps, null);
    }

    public ValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** 不存在 ERROR 级问题时为 true。 */
    public boolean valid() {
        return issues.stream().noneMatch(i -> i.severity() == ValidationSeverity.ERROR);
    }

    public List<ValidationIssue> errors() {
        return issues.stream().filter(i -> i.severity() == ValidationSeverity.ERROR).toList();
    }
}
