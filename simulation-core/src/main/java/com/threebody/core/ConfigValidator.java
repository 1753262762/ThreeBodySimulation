package com.threebody.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 配置校验与规范化。校验只返回问题列表，不抛异常；
 * 规范化会补齐缺失的天体 ID 与颜色，并保持字段顺序稳定。
 */
public final class ConfigValidator {

    /** 允许的最大步数上限，避免单个实验无限占用工作线程。 */
    public static final long MAX_ALLOWED_STEPS = 100_000_000L;

    /** 允许的最大目标模拟时间(s)，约 3.17e6 年。 */
    public static final double MAX_ALLOWED_TARGET_TIME_SECONDS = 1.0e14;

    private static final Pattern COLOR_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private static final String[] DEFAULT_PALETTE = {
            "#ffd166", "#4d96ff", "#ef476f", "#06d6a0", "#c77dff",
            "#f78c6b", "#66d9e8", "#ffe066", "#b5179e", "#48cae4",
            "#9ef01a", "#ff8fab", "#4361ee", "#ff9f1c", "#2ec4b6",
            "#e0aaff", "#f15bb5", "#00bbf9", "#fee440", "#9b5de5"
    };

    private ConfigValidator() {
    }

    /**
     * 校验并规范化配置。
     */
    public static ValidationResult validate(SimulationConfig config) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (config == null) {
            issues.add(ValidationIssue.error("config", ValidationCode.NON_FINITE_VALUE, "配置不能为空"));
            return new ValidationResult(issues, null, null);
        }

        validateGlobals(config, issues);
        validateBodies(config, issues);
        validateEndConditions(config, issues);

        ValidationResult probe = new ValidationResult(issues, null, null);
        if (!probe.valid()) {
            return new ValidationResult(issues, null, null);
        }

        SimulationConfig normalized = normalize(config);
        return new ValidationResult(issues, normalized, normalized.estimatedTotalSteps());
    }

    private static void validateGlobals(SimulationConfig config, List<ValidationIssue> issues) {
        if (!isPositiveFinite(config.timeStepSeconds())) {
            issues.add(ValidationIssue.error("timeStepSeconds", ValidationCode.INVALID_TIME_STEP,
                    "时间步长必须是大于 0 的有限值(s)"));
        }
        if (!isPositiveFinite(config.gravitationalConstant())) {
            issues.add(ValidationIssue.error("gravitationalConstant",
                    ValidationCode.INVALID_GRAVITATIONAL_CONSTANT,
                    "引力常数必须是大于 0 的有限值"));
        }
        double softening = config.softeningLengthMeters();
        if (!Double.isFinite(softening) || softening < 0.0) {
            issues.add(ValidationIssue.error("softeningLengthMeters", ValidationCode.INVALID_SOFTENING_LENGTH,
                    "软化长度必须是不小于 0 的有限值(m)"));
        } else if (softening == 0.0) {
            issues.add(ValidationIssue.warning("softeningLengthMeters", ValidationCode.INVALID_SOFTENING_LENGTH,
                    "软化长度为 0 时近距离可能出现数值不稳定，建议设置为特征距离的千分之一"));
        }
    }

    private static void validateBodies(SimulationConfig config, List<ValidationIssue> issues) {
        List<BodySpec> bodies = config.bodies();
        int count = bodies.size();
        if (count < PhysicalConstants.MIN_BODY_COUNT || count > PhysicalConstants.MAX_BODY_COUNT) {
            issues.add(ValidationIssue.error("bodies", ValidationCode.BODY_COUNT_OUT_OF_RANGE,
                    "天体数量必须在 " + PhysicalConstants.MIN_BODY_COUNT + " 到"
                            + PhysicalConstants.MAX_BODY_COUNT + " 之间，当前为 " + count));
            return;
        }

        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < count; i++) {
            BodySpec body = bodies.get(i);
            String prefix = "bodies[" + i + "]";
            if (body == null) {
                issues.add(ValidationIssue.error(prefix, ValidationCode.NON_FINITE_VALUE, "天体不能为空"));
                continue;
            }
            if (body.id() != null && !body.id().isBlank() && !seenIds.add(body.id())) {
                issues.add(ValidationIssue.error(prefix + ".id", ValidationCode.DUPLICATE_BODY_ID,
                        "天体 ID 重复：" + body.id()));
            }
            if (body.name() == null || body.name().isBlank()) {
                issues.add(ValidationIssue.error(prefix + ".name", ValidationCode.MISSING_BODY_NAME,
                        "天体名称不能为空"));
            } else if (body.name().length() > 40) {
                issues.add(ValidationIssue.error(prefix + ".name", ValidationCode.MISSING_BODY_NAME,
                        "天体名称长度不能超过 40 个字符"));
            }
            if (body.color() != null && !COLOR_PATTERN.matcher(body.color()).matches()) {
                issues.add(ValidationIssue.error(prefix + ".color", ValidationCode.INVALID_COLOR,
                        "颜色必须是 #RRGGBB 形式，当前为 " + body.color()));
            }
            if (!isPositiveFinite(body.massKg())) {
                issues.add(ValidationIssue.error(prefix + ".massKg", ValidationCode.INVALID_MASS,
                        "质量必须是大于 0 的有限值(kg)"));
            }
            if (body.position() == null || !body.position().isFinite()) {
                issues.add(ValidationIssue.error(prefix + ".position", ValidationCode.NON_FINITE_VALUE,
                        "初始位置必须是有限值(m)"));
            }
            if (body.velocity() == null || !body.velocity().isFinite()) {
                issues.add(ValidationIssue.error(prefix + ".velocity", ValidationCode.NON_FINITE_VALUE,
                        "初始速度必须是有限值(m/s)"));
            }
        }

        validateSeparation(config, issues);
    }

    /**
     * 完全重合的天体在软化引力下不会产生排斥，属于无意义配置，判定为错误；
     * 距离小于近距离阈值时给出警告。
     */
    private static void validateSeparation(SimulationConfig config, List<ValidationIssue> issues) {
        List<BodySpec> bodies = config.bodies();
        double threshold = config.nearEncounterThresholdMeters();
        for (int i = 0; i < bodies.size(); i++) {
            BodySpec a = bodies.get(i);
            if (a == null || a.position() == null || !a.position().isFinite()) {
                continue;
            }
            for (int j = i + 1; j < bodies.size(); j++) {
                BodySpec b = bodies.get(j);
                if (b == null || b.position() == null || !b.position().isFinite()) {
                    continue;
                }
                double distance = b.position().subtract(a.position()).length();
                if (distance == 0.0) {
                    issues.add(ValidationIssue.error("bodies[" + j + "].position",
                            ValidationCode.COINCIDENT_BODIES,
                            "天体 " + safeName(a) + " 与 " + safeName(b) + " 初始位置完全重合"));
                } else if (threshold > 0.0 && distance < threshold) {
                    issues.add(ValidationIssue.warning("bodies[" + j + "].position",
                            ValidationCode.COINCIDENT_BODIES,
                            "天体 " + safeName(a) + " 与 " + safeName(b) + " 初始距离小于近距离阈值 "
                                    + String.format(Locale.ROOT, "%.3e", threshold) + " m，启动即会触发近距离事件"));
                }
            }
        }
    }

    private static void validateEndConditions(SimulationConfig config, List<ValidationIssue> issues) {
        Long maxSteps = config.maxSteps();
        Double targetTime = config.targetSimulationTimeSeconds();
        if (maxSteps == null && targetTime == null) {
            issues.add(ValidationIssue.error("maxSteps", ValidationCode.MISSING_END_CONDITION,
                    "必须提供 maxSteps 或 targetSimulationTimeSeconds 作为结束条件"));
        }
        if (maxSteps != null && (maxSteps < 1 || maxSteps > MAX_ALLOWED_STEPS)) {
            issues.add(ValidationIssue.error("maxSteps", ValidationCode.MAX_STEPS_OUT_OF_RANGE,
                    "最大步数必须在 1 到 " + MAX_ALLOWED_STEPS + " 之间"));
        }
        if (targetTime != null
                && (!Double.isFinite(targetTime) || targetTime <= 0.0
                        || targetTime > MAX_ALLOWED_TARGET_TIME_SECONDS)) {
            issues.add(ValidationIssue.error("targetSimulationTimeSeconds",
                    ValidationCode.TARGET_TIME_OUT_OF_RANGE,
                    "目标模拟时间必须是 0 到 " + MAX_ALLOWED_TARGET_TIME_SECONDS + " 之间的有限值(s)"));
        }
    }

    /**
     * 补齐天体 ID 与颜色。
     */
    public static SimulationConfig normalize(SimulationConfig config) {
        List<BodySpec> normalized = new ArrayList<>(config.bodyCount());
        for (int i = 0; i < config.bodyCount(); i++) {
            BodySpec body = config.bodies().get(i);
            String id = body.id() == null || body.id().isBlank()
                    ? UUID.randomUUID().toString()
                    : body.id();
            String color = body.color() == null || body.color().isBlank()
                    ? DEFAULT_PALETTE[i % DEFAULT_PALETTE.length]
                    : body.color().toLowerCase(Locale.ROOT);
            normalized.add(new BodySpec(id, body.name().trim(), color,
                    body.massKg(), body.position(), body.velocity()));
        }
        return config.withBodies(normalized);
    }

    private static String safeName(BodySpec spec) {
        return spec.name() == null || spec.name().isBlank() ? "未命名" : spec.name();
    }

    private static boolean isPositiveFinite(double value) {
        return Double.isFinite(value) && value > 0.0;
    }
}