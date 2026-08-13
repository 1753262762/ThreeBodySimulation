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
 * 强制校验通过后执行量纲化风险规则，风险问题只返回每种风险码最严重的一条。
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

    /** 时间步风险：单步移动量占有效半径的比例阈值。 */
    private static final double MOVE_RATE_CAUTION = 0.05;
    private static final double MOVE_RATE_HIGH = 0.2;
    private static final double PERIOD_RATE_CAUTION = 1.0 / 100.0;
    private static final double PERIOD_RATE_HIGH = 1.0 / 20.0;
    private static final double GUIDANCE_MARGIN = 0.9;

    /** 软化长度过小/过大判定使用的 ε/rMin 阈值。 */
    private static final double SOFTENING_RATIO_TOO_SMALL = 1e-6;
    private static final double SOFTENING_RATIO_TOO_LARGE = 0.1;

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

        if (issues.stream().anyMatch(i -> i.severity() == ValidationSeverity.ERROR)) {
            return new ValidationResult(issues, null, null);
        }

        SimulationConfig normalized = normalize(config);
        validateRisks(normalized, issues);
        return new ValidationResult(issues, normalized, normalized.estimatedTotalSteps(),
                summarize(normalized));
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

        validateCoincidence(config, issues);
    }

    /**
     * 完全重合的天体在软化引力下不会产生排斥，属于无意义配置，判定为错误；
     * 初始距离小于近遇阈值属于风险问题，由风险规则给出 HIGH 提示。
     */
    private static void validateCoincidence(SimulationConfig config, List<ValidationIssue> issues) {
        List<BodySpec> bodies = config.bodies();
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
     * 量纲化风险规则。强制校验全部通过后才执行；每种风险码只返回最严重的一条。
     * 启发式提示可能的不稳定来源，不得写成确定物理结论。
     */
    private static void validateRisks(SimulationConfig config, List<ValidationIssue> issues) {
        double dt = config.timeStepSeconds();
        double g = config.gravitationalConstant();
        double eps = config.softeningLengthMeters();
        List<BodySpec> bodies = config.bodies();
        int n = bodies.size();
        if (n < 2 || !Double.isFinite(dt) || dt <= 0.0) {
            return;
        }

        double minPeriod = Double.POSITIVE_INFINITY;
        String minPeriodPair = null;
        List<String> minPeriodBodyIds = List.of();
        double minPeriodRatio = 0.0;

        double maxMoveRate = 0.0;
        String maxMovePair = null;
        List<String> maxMoveBodyIds = List.of();
        double maxMoveDistanceMeters = 0.0;
        double maxMoveReferenceMeters = 0.0;

        double rMin = Double.POSITIVE_INFINITY;
        List<String> rMinBodyIds = List.of();
        double minDistBelow5Eps = Double.POSITIVE_INFINITY;
        String minDistPair = null;
        List<String> minDistBodyIds = List.of();

        for (int i = 0; i < n; i++) {
            BodySpec a = bodies.get(i);
            if (a == null || a.position() == null || a.velocity() == null
                    || !a.position().isFinite() || !a.velocity().isFinite()) {
                continue;
            }
            for (int j = i + 1; j < n; j++) {
                BodySpec b = bodies.get(j);
                if (b == null || b.position() == null || b.velocity() == null
                        || !b.position().isFinite() || !b.velocity().isFinite()) {
                    continue;
                }
                double r = b.position().subtract(a.position()).length();
                double rEff = Math.max(r, Math.max(eps, Double.MIN_NORMAL));
                double mu = g * (a.massKg() + b.massKg());
                double vRel = b.velocity().subtract(a.velocity()).length();
                double period = 2.0 * Math.PI * Math.sqrt(rEff * rEff * rEff / mu);
                double moveRate = vRel * dt / rEff;
                String pairName = safeName(a) + " 与 " + safeName(b);

                if (r < rMin) {
                    rMin = r;
                    rMinBodyIds = bodyIds(a, b);
                }
                if (period < minPeriod) {
                    minPeriod = period;
                    minPeriodPair = pairName;
                    minPeriodRatio = dt / period;
                    minPeriodBodyIds = bodyIds(a, b);
                }
                if (moveRate > maxMoveRate) {
                    maxMoveRate = moveRate;
                    maxMovePair = pairName;
                    maxMoveBodyIds = bodyIds(a, b);
                    maxMoveDistanceMeters = vRel * dt;
                    maxMoveReferenceMeters = rEff;
                }
                if (eps > 0.0 && r < PhysicalConstants.NEAR_ENCOUNTER_SOFTENING_FACTOR * eps
                        && r < minDistBelow5Eps) {
                    minDistBelow5Eps = r;
                    minDistPair = pairName;
                    minDistBodyIds = bodyIds(a, b);
                }
            }
        }

        boolean timeStepHigh = Double.isFinite(minPeriod) && minPeriodRatio > PERIOD_RATE_HIGH
                || maxMoveRate > MOVE_RATE_HIGH;
        boolean timeStepCaution = Double.isFinite(minPeriod) && minPeriodRatio > PERIOD_RATE_CAUTION
                || maxMoveRate > MOVE_RATE_CAUTION;
        double suggestedDt = suggestedTimeStep(dt, minPeriodRatio, maxMoveRate);
        ValidationGuidance timeStepGuidance = new ValidationGuidance(
                "当前时间步长相对系统中最快的局部运动偏大。",
                "一次积分可能跨过过多轨道变化，使能量与角动量误差增大，甚至导致数值失败。",
                timeStepEvidence(dt, suggestedDt, minPeriod, minPeriodRatio, minPeriodBodyIds,
                        maxMoveDistanceMeters, maxMoveReferenceMeters, maxMoveRate, maxMoveBodyIds),
                new GuidanceAction("REDUCE_TIME_STEP", GuidanceActionMode.APPLY_PATCH,
                        "减小时间步长",
                        "让局部周期比例和单步位移比例回到注意阈值以内，并保留 10% 余量。",
                        "预计积分步数会增加；若保持模拟时长，最大步数也需要同比增加。",
                        new GuidanceConfigPatch(suggestedDt, null),
                        GuidanceAdjustmentPolicy.PRESERVE_SIMULATION_DURATION),
                List.of(new GuidanceAction("REVIEW_FASTEST_PAIR", GuidanceActionMode.MANUAL_REVIEW,
                        "检查最快变化的天体对",
                        "确认当前初始位置和速度是否确实是要研究的条件。",
                        "修改初始条件会改变研究问题，不能再只比较积分精度。",
                        null, null)));
        if (timeStepHigh) {
            issues.add(ValidationIssue.risk("timeStepSeconds", ValidationCode.TIME_STEP_TOO_LARGE,
                    "时间步长相对最近轨道周期过大，可能漏过快速轨道运动；减小步长会增加计算量但通常改善稳定性。"
                            + "当前最严重对为 " + minPeriodPair + "，dt/period="
                            + formatRatio(minPeriodRatio) + "，单步位移比例=" + formatRatio(maxMoveRate),
                    RiskLevel.HIGH, timeStepGuidance));
        } else if (timeStepCaution) {
            issues.add(ValidationIssue.risk("timeStepSeconds", ValidationCode.TIME_STEP_TOO_LARGE,
                    "时间步长相对部分轨道周期偏大，建议检查是否漏过快速运动；减小步长会增加计算量但通常改善稳定性。"
                            + "当前最严重对为 " + minPeriodPair + "，dt/period="
                            + formatRatio(minPeriodRatio) + "，单步位移比例=" + formatRatio(maxMoveRate),
                    RiskLevel.CAUTION, timeStepGuidance));
        }

        SpeedEscape worst = worstSpeedEscape(bodies, g, eps);
        if (worst != null) {
            if (worst.speedRate() > 5.0) {
                issues.add(ValidationIssue.risk(
                        "bodies[" + worst.index() + "].velocity",
                        ValidationCode.INITIAL_SPEED_HIGH,
                        "相对系统质心的速度远高于逃逸速度，可能是合法的非束缚/逃逸初始条件，也可能快速解体。"
                                + "最危险天体为 " + worst.name() + "，speedRate=" + formatRatio(worst.speedRate()),
                        RiskLevel.HIGH,
                        speedGuidance(worst, RiskLevel.HIGH)));
            } else if (worst.speedRate() > 2.0) {
                issues.add(ValidationIssue.risk(
                        "bodies[" + worst.index() + "].velocity",
                        ValidationCode.INITIAL_SPEED_HIGH,
                        "相对系统质心的速度高于逃逸速度，可能进入非束缚/逃逸轨道，也可能导致系统快速发散。"
                                + "最危险天体为 " + worst.name() + "，speedRate=" + formatRatio(worst.speedRate()),
                        RiskLevel.CAUTION,
                        speedGuidance(worst, RiskLevel.CAUTION)));
            }
        }

        double epsRatio = Double.isFinite(rMin) && rMin > 0.0 ? eps / rMin : Double.POSITIVE_INFINITY;
        if (eps == 0.0 || epsRatio < SOFTENING_RATIO_TOO_SMALL) {
            RiskLevel level = timeStepHigh ? RiskLevel.HIGH : RiskLevel.CAUTION;
            String suffix = eps == 0.0
                    ? "软化长度为 0 时近遇阈值为 0，不会产生 Close Encounter 事件；"
                    : "ε/rMin=" + formatRatio(epsRatio) + "，近接保护较弱；";
            issues.add(ValidationIssue.risk("softeningLengthMeters", ValidationCode.SOFTENING_TOO_SMALL,
                    suffix + "建议结合目标近距离尺度人工检查软化长度。", level,
                    softeningGuidance(eps, rMin, epsRatio, rMinBodyIds, true)));
        }
        if (epsRatio > SOFTENING_RATIO_TOO_LARGE) {
            issues.add(ValidationIssue.risk("softeningLengthMeters", ValidationCode.SOFTENING_TOO_LARGE,
                    "软化长度相对初始最近距离过大（ε/rMin=" + formatRatio(epsRatio)
                            + "），近距离引力会被明显抹平，结果可能偏离目标物理模型。",
                    RiskLevel.HIGH, softeningGuidance(eps, rMin, epsRatio, rMinBodyIds, false)));
        }

        if (Double.isFinite(minDistBelow5Eps)) {
            issues.add(ValidationIssue.risk(fieldForPair(bodies, minDistPair, "position"),
                    ValidationCode.INITIAL_DISTANCE_TOO_SMALL,
                    "初始距离小于近遇阈值，启动即进入 Close Encounter（不宣称一定发生碰撞）。"
                            + "最严重对为 " + minDistPair + "，距离="
                            + String.format(Locale.ROOT, "%.3e", minDistBelow5Eps) + " m。",
                    RiskLevel.HIGH,
                    closeDistanceGuidance(minDistBelow5Eps, eps, minDistBodyIds)));
        }
    }

    private static ConfigSummary summarize(SimulationConfig config) {
        long maxCandidate = config.maxSteps() != null ? config.maxSteps() : Long.MAX_VALUE;
        long targetCandidate = Long.MAX_VALUE;
        if (config.targetSimulationTimeSeconds() != null) {
            double raw = config.targetSimulationTimeSeconds() / config.timeStepSeconds();
            targetCandidate = raw >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.ceil(raw);
        }
        LimitingEndCondition limiting = maxCandidate == targetCandidate
                ? LimitingEndCondition.BOTH
                : maxCandidate < targetCandidate
                        ? LimitingEndCondition.MAX_STEPS
                        : LimitingEndCondition.TARGET_TIME;
        double minDistance = Double.POSITIVE_INFINITY;
        List<String> pairIds = List.of();
        for (int i = 0; i < config.bodies().size(); i++) {
            BodySpec a = config.bodies().get(i);
            for (int j = i + 1; j < config.bodies().size(); j++) {
                BodySpec b = config.bodies().get(j);
                double distance = b.position().subtract(a.position()).length();
                if (distance < minDistance) {
                    minDistance = distance;
                    pairIds = bodyIds(a, b);
                }
            }
        }
        Long steps = config.estimatedTotalSteps();
        Double duration = steps == null ? null : steps * config.timeStepSeconds();
        Double ratio = Double.isFinite(minDistance) && minDistance > 0.0
                ? config.softeningLengthMeters() / minDistance
                : null;
        return new ConfigSummary(steps, duration, limiting,
                Double.isFinite(minDistance) ? minDistance : null, pairIds, ratio);
    }

    private static double suggestedTimeStep(double currentDt, double periodRatio, double moveRate) {
        double factor = 1.0;
        if (periodRatio > 0.0 && Double.isFinite(periodRatio)) {
            factor = Math.min(factor, PERIOD_RATE_CAUTION * GUIDANCE_MARGIN / periodRatio);
        }
        if (moveRate > 0.0 && Double.isFinite(moveRate)) {
            factor = Math.min(factor, MOVE_RATE_CAUTION * GUIDANCE_MARGIN / moveRate);
        }
        double suggested = currentDt * factor;
        return suggested > 0.0 && Double.isFinite(suggested) ? suggested : currentDt / 10.0;
    }

    private static List<GuidanceEvidence> timeStepEvidence(double dt, double suggestedDt,
            double minPeriod, double minPeriodRatio, List<String> minPeriodBodyIds,
            double moveDistance, double moveReference, double moveRate, List<String> moveBodyIds) {
        List<GuidanceEvidence> evidence = new ArrayList<>();
        evidence.add(new GuidanceEvidence("TIME_STEP_SECONDS", dt, suggestedDt, null, List.of()));
        if (Double.isFinite(minPeriod) && Double.isFinite(minPeriodRatio)) {
            evidence.add(new GuidanceEvidence("LOCAL_PERIOD_SECONDS", minPeriod,
                    minPeriod * PERIOD_RATE_CAUTION, minPeriodRatio, minPeriodBodyIds));
        }
        if (Double.isFinite(moveDistance) && Double.isFinite(moveReference) && Double.isFinite(moveRate)) {
            evidence.add(new GuidanceEvidence("ONE_STEP_RELATIVE_MOVEMENT_METERS", moveDistance,
                    moveReference, moveRate, moveBodyIds));
        }
        return evidence;
    }

    private static ValidationGuidance speedGuidance(SpeedEscape worst, RiskLevel level) {
        return new ValidationGuidance(
                "天体 " + worst.name() + " 相对其余系统向外运动，速度超过局部逃逸尺度。",
                "系统可能很快变成非束缚状态；这可以是有意的物理初始条件，并不等于程序错误。",
                List.of(new GuidanceEvidence("SPEED_TO_ESCAPE_RATIO", worst.speedRate(), 1.0,
                        worst.speedRate(), List.of(worst.bodyId()))),
                new GuidanceAction("REVIEW_INITIAL_VELOCITY", GuidanceActionMode.MANUAL_REVIEW,
                        "检查该天体的初始速度", "确认速度大小和方向是否符合实验目的。",
                        "修改速度会改变所研究的物理系统。", null, null),
                level == RiskLevel.HIGH
                        ? List.of(new GuidanceAction("RUN_AS_UNBOUND_SYSTEM", GuidanceActionMode.MANUAL_REVIEW,
                                "保留配置并观察逃逸", "若目标就是研究非束缚系统，可以保留当前设置。",
                                "结果应被解释为主动选择的非束缚初始条件。", null, null))
                        : List.of());
    }

    private static ValidationGuidance softeningGuidance(double eps, double rMin, double ratio,
            List<String> bodyIds, boolean tooSmall) {
        return new ValidationGuidance(
                tooSmall ? "软化长度相对初始最近距离很小。" : "软化长度已接近初始最近距离的显著比例。",
                tooSmall
                        ? "近遇时引力变化会更尖锐，需要更小时间步长；软化为 0 时也不会生成近遇阈值事件。"
                        : "近距离引力会被明显平滑，可能改变要研究的物理模型。",
                List.of(new GuidanceEvidence("SOFTENING_LENGTH_METERS", eps, rMin, ratio, bodyIds)),
                new GuidanceAction("REVIEW_SOFTENING", GuidanceActionMode.MANUAL_REVIEW,
                        "人工检查软化长度", "结合希望分辨的最近距离选择软化尺度。",
                        "修改软化长度会改变近距离物理模型，因此系统不会自动应用。", null, null),
                List.of());
    }

    private static ValidationGuidance closeDistanceGuidance(double distance, double eps,
            List<String> bodyIds) {
        return new ValidationGuidance(
                "一对天体在模拟开始时已经位于近遇阈值内。",
                "运行会从近距离强相互作用开始；这不表示一定碰撞，但会提高时间分辨率要求。",
                List.of(new GuidanceEvidence("INITIAL_PAIR_DISTANCE_METERS", distance,
                        PhysicalConstants.NEAR_ENCOUNTER_SOFTENING_FACTOR * eps,
                        eps > 0.0 ? distance / eps : null, bodyIds)),
                new GuidanceAction("REVIEW_INITIAL_POSITION", GuidanceActionMode.MANUAL_REVIEW,
                        "检查初始位置", "确认这种近距离起始状态是否符合实验目的。",
                        "修改位置会改变研究问题，系统不会自动应用。", null, null),
                List.of(new GuidanceAction("REDUCE_TIME_STEP_FOR_ENCOUNTER", GuidanceActionMode.MANUAL_REVIEW,
                        "考虑减小时间步长", "若保留近距离起点，更小步长通常能更好分辨快速变化。",
                        "积分步数和计算量会增加。", null, null)));
    }

    private static List<String> bodyIds(BodySpec a, BodySpec b) {
        return List.of(a.id(), b.id());
    }

    private static String fieldForPair(List<BodySpec> bodies, String pairName, String suffix) {
        if (pairName == null) {
            return "bodies";
        }
        for (int i = 0; i < bodies.size(); i++) {
            if (bodies.get(i) != null && safeName(bodies.get(i)).equals(pairName.split(" 与 ")[0])) {
                return "bodies[" + i + "]." + suffix;
            }
        }
        return "bodies";
    }

    private static String formatRatio(double ratio) {
        if (!Double.isFinite(ratio)) {
            return "N/A";
        }
        return String.format(Locale.ROOT, "%.3e", ratio);
    }

    /**
     * 按相对"其余天体"质心的系统级逃逸速度计算每个天体的 speedRate，
     * 仅返回向外运动且最接近逃逸的天体。与诊断 POSSIBLE_ESCAPE 使用同一判定，
     * 避免层次束缚系统（如预设 A）被逐对公式误报。
     */
    private static SpeedEscape worstSpeedEscape(List<BodySpec> bodies, double g, double eps) {
        double totalMass = 0.0;
        Vector3 totalPosition = Vector3.ZERO;
        Vector3 totalVelocity = Vector3.ZERO;
        int n = bodies.size();
        for (BodySpec body : bodies) {
            if (body == null || body.massKg() <= 0.0 || !Double.isFinite(body.massKg())) {
                return null;
            }
            totalMass += body.massKg();
            totalPosition = totalPosition.add(body.position().multiply(body.massKg()));
            totalVelocity = totalVelocity.add(body.velocity().multiply(body.massKg()));
        }
        if (totalMass <= 0.0) {
            return null;
        }
        Vector3 systemComPosition = totalPosition.multiply(1.0 / totalMass);
        Vector3 systemComVelocity = totalVelocity.multiply(1.0 / totalMass);

        SpeedEscape worst = null;
        for (int i = 0; i < n; i++) {
            BodySpec body = bodies.get(i);
            if (body == null || body.position() == null || body.velocity() == null
                    || !body.position().isFinite() || !body.velocity().isFinite()) {
                continue;
            }
            double restMass = totalMass - body.massKg();
            if (restMass <= 0.0) {
                continue;
            }
            Vector3 restComPosition = systemComPosition
                    .multiply(totalMass)
                    .subtract(body.position().multiply(body.massKg()))
                    .multiply(1.0 / restMass);
            Vector3 restComVelocity = systemComVelocity
                    .multiply(totalMass)
                    .subtract(body.velocity().multiply(body.massKg()))
                    .multiply(1.0 / restMass);
            Vector3 r = body.position().subtract(restComPosition);
            Vector3 v = body.velocity().subtract(restComVelocity);
            boolean outward = r.dot(v) > 0.0;
            if (!outward) {
                continue;
            }
            double rEff = Math.max(r.length(), Math.max(eps, Double.MIN_NORMAL));
            double vEscape = Math.sqrt(2.0 * g * (body.massKg() + restMass) / rEff);
            double speedRate = v.length() / vEscape;
            if (!Double.isFinite(speedRate)) {
                continue;
            }
            if (worst == null || speedRate > worst.speedRate()) {
                worst = new SpeedEscape(i, body.id(), safeName(body), speedRate);
            }
        }
        return worst;
    }

    private record SpeedEscape(int index, String bodyId, String name, double speedRate) {}

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
