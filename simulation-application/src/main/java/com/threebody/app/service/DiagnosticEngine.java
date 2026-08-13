package com.threebody.app.service;

import com.threebody.app.domain.Diagnostic;
import com.threebody.app.domain.DiagnosticCauseCategory;
import com.threebody.app.domain.DiagnosticEvidence;
import com.threebody.app.domain.DiagnosticSeverity;
import com.threebody.core.BodyState;
import com.threebody.core.ConfigValidator;
import com.threebody.core.Metrics;
import com.threebody.core.SimulationConfig;
import com.threebody.core.SimulationState;
import com.threebody.core.ValidationIssue;
import com.threebody.core.ValidationResult;
import com.threebody.core.ValidationSeverity;
import com.threebody.core.Vector3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 结构化运行时诊断引擎。只在跨越等级边界时产生一次性 DIAGNOSTIC 事件；
 * 同一诊断键在冷却期内不重复生成，严重度升级可以绕过冷却。
 * 默认诊断键为 code + 排序后的 bodyIds，冷却期为 10 个指标发布周期。
 */
public final class DiagnosticEngine {

    /** 冷却期：指标发布周期数。 */
    public static final int COOLDOWN_PERIODS = 10;

    private static final double ESCAPE_WARNING_RATIO = 2.0;
    private static final double ESCAPE_CRITICAL_RATIO = 5.0;

    private static final double DEFLECTION_DEGREES = 30.0;
    private static final double DISASSEMBLY_RADIUS_RATIO = 4.0;
    private static final int DISASSEMBLY_CONSECUTIVE_PERIODS = 3;

    private final SimulationConfig config;
    private final double initialRmsRadius;
    private final boolean hadHighStartupRisk;

    /** 每个诊断键当前已发布的最高严重度（升级可绕过冷却）。 */
    private final Map<String, DiagnosticSeverity> lastSeverity = new LinkedHashMap<>();

    /** 每个诊断键的下次允许发布周期。 */
    private final Map<String, Long> cooldownUntil = new LinkedHashMap<>();

    private final Map<String, Vector3> previousVelocities = new LinkedHashMap<>();

    private int consecutiveDisassemblyPeriods = 0;
    private boolean disassemblyArmed = true;
    private long currentMetricIndex;

    public DiagnosticEngine(SimulationConfig config, SimulationState initialState) {
        this.config = config;
        this.initialRmsRadius = rmsRadius(config, initialState);
        this.hadHighStartupRisk = hasHighStartupRisk(config);
    }

    /** 记录一步速度，供剧烈偏转检测。 */
    public void observeStep(SimulationState state) {
        for (BodyState body : state.bodies()) {
            previousVelocities.put(body.id(), body.velocity());
        }
    }

    /**
     * 每个指标发布周期调用一次，返回本次要发布的新诊断。
     */
    public List<Diagnostic> evaluate(SimulationState state, Metrics metrics, boolean hasActiveEncounter) {
        currentMetricIndex++;
        List<Diagnostic> found = new ArrayList<>();

        Diagnostic escape = evaluateEscape(state);
        Diagnostic deflection = evaluateDeflection(state, hasActiveEncounter);
        Diagnostic disassembly = evaluateDisassembly(state);

        List<Diagnostic> physical = new ArrayList<>();
        if (escape != null) physical.add(escape);
        if (deflection != null) physical.add(deflection);
        if (disassembly != null) physical.add(disassembly);

        for (Diagnostic d : physical) {
            found.add(withCauseCategory(d, DiagnosticCauseCategory.PHYSICAL_PHENOMENON));
        }
        return found;
    }

    private Diagnostic evaluateEscape(SimulationState state) {
        double totalMass = 0.0;
        Vector3 totalPosition = Vector3.ZERO;
        Vector3 totalVelocity = Vector3.ZERO;
        for (BodyState body : state.bodies()) {
            totalMass += configBodyMass(body.id());
            totalPosition = totalPosition.add(body.position().multiply(configBodyMass(body.id())));
            totalVelocity = totalVelocity.add(body.velocity().multiply(configBodyMass(body.id())));
        }
        if (totalMass <= 0.0) {
            return null;
        }
        Vector3 comPosition = totalPosition.multiply(1.0 / totalMass);
        Vector3 comVelocity = totalVelocity.multiply(1.0 / totalMass);

        DiagnosticSeverity worst = null;
        double worstRatio = 0.0;
        List<String> worstBodyIds = new ArrayList<>();
        String worstName = null;
        double worstEscape = 0.0;
        for (BodyState body : state.bodies()) {
            double mass = configBodyMass(body.id());
            double restMass = totalMass - mass;
            if (restMass <= 0.0) {
                continue;
            }
            Vector3 r = body.position().subtract(comPosition);
            Vector3 v = body.velocity().subtract(comVelocity);
            boolean outward = r.dot(v) > 0.0;
            if (!outward) {
                continue;
            }
            double rEff = Math.max(r.length(), Math.max(config.softeningLengthMeters(), Double.MIN_NORMAL));
            double vEscape = Math.sqrt(2.0 * config.gravitationalConstant() * totalMass / rEff);
            double ratio = v.length() / vEscape;
            DiagnosticSeverity sev = null;
            if (ratio > ESCAPE_CRITICAL_RATIO) {
                sev = DiagnosticSeverity.CRITICAL;
            } else if (ratio > ESCAPE_WARNING_RATIO) {
                sev = DiagnosticSeverity.WARNING;
            }
            if (sev != null && (worst == null || sev.ordinal() > worst.ordinal())) {
                worst = sev;
                worstRatio = ratio;
                worstBodyIds = List.of(body.id());
                worstName = bodyName(body.id());
                worstEscape = vEscape;
            }
        }
        if (worst == null) {
            return null;
        }
        String key = diagKey("POSSIBLE_ESCAPE", worstBodyIds);
        if (!canEmit(key, worst)) {
            return null;
        }
        DiagnosticEvidence evidence = new DiagnosticEvidence(
                config.timeStepSeconds(), config.softeningLengthMeters(),
                null, null, null, worstRatio, null, null, null, null, worstBodyIds);
        return new Diagnostic(
                "POSSIBLE_ESCAPE",
                worst,
                DiagnosticCauseCategory.PHYSICAL_PHENOMENON,
                severityText(worst, "天体 " + worstName + " 相对其余天体质心向外运动，速度可能超过局部逃逸速度，"
                        + "可能进入非束缚/逃逸轨道。"),
                List.of("初始速度高于逃逸速度", "近距离引力相互作用改变了轨道能量"),
                evidence,
                List.of("如需研究逃逸，可增大模拟范围观察轨迹", "若不希望逃逸，请减小该天体初速度"));
    }

    private Diagnostic evaluateDeflection(SimulationState state, boolean hasActiveEncounter) {
        double worstAngle = 0.0;
        String worstName = null;
        List<String> worstBodyIds = new ArrayList<>();
        double worstDistance = 0.0;
        for (BodyState body : state.bodies()) {
            Vector3 previous = previousVelocities.get(body.id());
            if (previous == null || !previous.isFinite() || !body.velocity().isFinite()) {
                continue;
            }
            double prevLen = previous.length();
            double curLen = body.velocity().length();
            if (prevLen <= 0.0 || curLen <= 0.0) {
                continue;
            }
            double dot = previous.dot(body.velocity()) / (prevLen * curLen);
            dot = Math.max(-1.0, Math.min(1.0, dot));
            double angleDegrees = Math.toDegrees(Math.acos(dot));
            if (angleDegrees > worstAngle) {
                worstAngle = angleDegrees;
                worstName = bodyName(body.id());
                worstBodyIds = List.of(body.id());
                worstDistance = state.bodies().stream()
                        .mapToDouble(b -> b.position().subtract(body.position()).length())
                        .filter(d -> d > 0.0).min().orElse(Double.NaN);
            }
        }
        if (worstAngle <= DEFLECTION_DEGREES) {
            return null;
        }
        String key = diagKey("SUDDEN_DEFLECTION", worstBodyIds);
        DiagnosticSeverity severity = DiagnosticSeverity.WARNING;
        if (!canEmit(key, severity)) {
            return null;
        }
        DiagnosticEvidence evidence = new DiagnosticEvidence(
                config.timeStepSeconds(), config.softeningLengthMeters(),
                null, null, Double.isFinite(worstDistance) ? worstDistance : null,
                null, worstAngle, null, null, null, worstBodyIds);
        String context = hasActiveEncounter ? "，可能伴随近距离相互作用" : "";
        return new Diagnostic(
                "SUDDEN_DEFLECTION",
                severity,
                DiagnosticCauseCategory.PHYSICAL_PHENOMENON,
                severityText(severity, "天体 " + worstName + " 单步速度方向偏转约 "
                        + String.format(Locale.ROOT, "%.1f", worstAngle) + "°，可能发生剧烈偏转"
                        + context + "。"),
                List.of("近距离引力作用", "时间步长过大导致轨道失真", "数值不稳定"),
                evidence,
                List.of("建议减小时间步长后重新运行", "可增大软化长度以平滑近距离引力"));
    }

    private Diagnostic evaluateDisassembly(SimulationState state) {
        double currentRms = rmsRadius(config, state);
        if (initialRmsRadius <= 0.0) {
            return null;
        }
        double ratio = currentRms / initialRmsRadius;
        boolean expanded = ratio > DISASSEMBLY_RADIUS_RATIO;
        boolean outward = outwardFraction(state) >= disassemblyMinOutwardFraction(state.bodies().size());
        if (expanded && outward) {
            consecutiveDisassemblyPeriods++;
        } else {
            consecutiveDisassemblyPeriods = 0;
            disassemblyArmed = true;
        }
        if (consecutiveDisassemblyPeriods < DISASSEMBLY_CONSECUTIVE_PERIODS) {
            return null;
        }
        List<String> allIds = state.bodies().stream().map(BodyState::id).toList();
        String key = diagKey("RAPID_DISASSEMBLY", allIds);
        DiagnosticSeverity severity = DiagnosticSeverity.WARNING;
        if (!canEmit(key, severity)) {
            return null;
        }
        DiagnosticEvidence evidence = new DiagnosticEvidence(
                config.timeStepSeconds(), config.softeningLengthMeters(),
                null, null, null, null, null, ratio, outwardFraction(state), null, allIds);
        return new Diagnostic(
                "RAPID_DISASSEMBLY",
                severity,
                DiagnosticCauseCategory.PHYSICAL_PHENOMENON,
                severityText(severity, "系统质量加权 RMS 半径扩大超过 " + DISASSEMBLY_RADIUS_RATIO
                        + " 倍且大部分天体向外运动，系统可能正在快速解体。"),
                List.of("部分天体速度超过系统逃逸速度", "近距离相互作用将动能转移给外围天体"),
                evidence,
                List.of("若需长期结构，请检查初始速度是否过高", "可减小外围天体初速度后重新运行"));
    }

    /** 非有限结果诊断（由实验失败路径调用）。 */
    public static Diagnostic numericalInstability(SimulationConfig config, long lastStableStep,
            List<String> bodyIds, double minimumPairDistanceMeters) {
        DiagnosticEvidence evidence = new DiagnosticEvidence(
                config.timeStepSeconds(), config.softeningLengthMeters(),
                null, null, Double.isFinite(minimumPairDistanceMeters) ? minimumPairDistanceMeters : null,
                null, null, null, null, lastStableStep, bodyIds);
        return new Diagnostic(
                "NUMERICAL_INSTABILITY",
                DiagnosticSeverity.CRITICAL,
                DiagnosticCauseCategory.NUMERICAL_ERROR,
                "积分在第 " + lastStableStep + " 步出现非有限数值，实验已终止；最后稳定步状态已保留。",
                List.of("时间步长过大", "软化长度不足导致近距离加速度发散"),
                evidence,
                List.of("减小时间步长", "增大软化长度", "调整初始距离避免过近"));
    }

    // ============================ 冷却与武装 ============================

    private boolean canEmit(String key, DiagnosticSeverity severity) {
        DiagnosticSeverity previous = lastSeverity.get(key);
        boolean first = previous == null;
        boolean escalated = previous != null && severity.ordinal() > previous.ordinal();
        if (first || escalated) {
            lastSeverity.put(key, severity);
            cooldownUntil.put(key, currentMetricIndex + COOLDOWN_PERIODS);
            return true;
        }
        return isCooldownPassed(key);
    }

    private boolean isCooldownPassed(String key) {
        return !cooldownUntil.containsKey(key) || currentMetricIndex >= cooldownUntil.get(key);
    }

    // ============================ 辅助 ============================

    private Diagnostic withCauseCategory(Diagnostic d, DiagnosticCauseCategory category) {
        return new Diagnostic(d.code(), d.severity(), category, d.summary(),
                d.likelyCauses(), d.evidence(), d.recommendations());
    }

    private static String diagKey(String code, List<String> bodyIds) {
        return code + ":" + String.join(",", bodyIds.stream().sorted().toList());
    }

    private static String severityText(DiagnosticSeverity severity, String message) {
        return switch (severity) {
            case NOTICE -> "提示：" + message;
            case WARNING -> "警告：" + message;
            case CRITICAL -> "严重：" + message;
        };
    }

    private double rmsRadius(SimulationConfig config, SimulationState state) {
        double totalMass = 0.0;
        Vector3 com = Vector3.ZERO;
        Map<String, Double> masses = new LinkedHashMap<>();
        for (BodyState body : state.bodies()) {
            double m = configBodyMass(body.id());
            masses.put(body.id(), m);
            totalMass += m;
            com = com.add(body.position().multiply(m));
        }
        if (totalMass <= 0.0) {
            return 0.0;
        }
        com = com.multiply(1.0 / totalMass);
        double sum = 0.0;
        for (BodyState body : state.bodies()) {
            double m = masses.getOrDefault(body.id(), 0.0);
            sum += m * body.position().subtract(com).squaredLength();
        }
        return Math.sqrt(sum / totalMass);
    }

    private double outwardFraction(SimulationState state) {
        double totalMass = 0.0;
        Vector3 com = Vector3.ZERO;
        for (BodyState body : state.bodies()) {
            double m = configBodyMass(body.id());
            totalMass += m;
            com = com.add(body.position().multiply(m));
        }
        if (totalMass <= 0.0) {
            return 0.0;
        }
        com = com.multiply(1.0 / totalMass);
        int outwardCount = 0;
        for (BodyState body : state.bodies()) {
            Vector3 r = body.position().subtract(com);
            if (r.dot(body.velocity()) > 0.0) {
                outwardCount++;
            }
        }
        return (double) outwardCount / state.bodies().size();
    }

    private static double disassemblyMinOutwardFraction(int count) {
        return Math.ceil(2.0 * count / 3.0) / Math.max(1, count);
    }

    private double configBodyMass(String id) {
        for (com.threebody.core.BodySpec body : config.bodies()) {
            if (body.id().equals(id)) {
                return body.massKg();
            }
        }
        return 0.0;
    }

    private String bodyName(String id) {
        for (com.threebody.core.BodySpec body : config.bodies()) {
            if (body.id().equals(id)) {
                return body.name() != null ? body.name() : id;
            }
        }
        return id;
    }

    private static boolean hasHighStartupRisk(SimulationConfig config) {
        ValidationResult result = ConfigValidator.validate(config);
        return result.issues().stream()
                .anyMatch(i -> i.severity() == ValidationSeverity.WARNING
                        && i.riskLevel() == com.threebody.core.RiskLevel.HIGH);
    }
}
