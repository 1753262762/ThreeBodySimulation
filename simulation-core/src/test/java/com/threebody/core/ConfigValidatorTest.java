package com.threebody.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfigValidatorTest {

    private static SimulationConfig validConfig() {
        return new SimulationConfig(
                "校验用例",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 1.0e30, Vector3.of(-1.0e11, 0, 0), Vector3.of(0, -1.3e4, 0)),
                        new BodySpec("b", "乙", "#4d96ff", 1.0e30, Vector3.of(1.0e11, 0, 0), Vector3.of(0, 1.3e4, 0))),
                3600.0,
                PhysicalConstants.GRAVITATIONAL_CONSTANT,
                1.0e7,
                10_000L,
                null);
    }

    @Test
    @DisplayName("合法配置通过且返回归一化配置与预计步数")
    void validConfigNormalizes() {
        ValidationResult result = ConfigValidator.validate(validConfig());
        assertTrue(result.valid());
        assertNotNull(result.normalizedConfig());
        assertEquals(10_000L, result.estimatedSteps());
    }

    @Test
    @DisplayName("ERROR 问题的 riskLevel 必须为 null")
    void errorHasNullRiskLevel() {
        SimulationConfig config = new SimulationConfig(
                "非法质量",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 0.0, Vector3.ZERO, Vector3.ZERO),
                        new BodySpec("b", "乙", "#4d96ff", -1.0, Vector3.of(1.0e11, 0, 0), Vector3.ZERO)),
                3600.0, PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0e7, 10_000L, null);
        ValidationResult result = ConfigValidator.validate(config);
        assertFalse(result.valid());
        for (ValidationIssue issue : result.errors()) {
            assertNull(issue.riskLevel(), "ERROR 问题 riskLevel 必须为 null");
        }
    }

    @Test
    @DisplayName("强制校验覆盖数量、重合、结束条件与字段边界")
    void mandatoryBoundaries() {
        SimulationConfig base = validConfig();
        assertFalse(ConfigValidator.validate(base.withBodies(List.of())).valid(), "空天体列表应失败");

        SimulationConfig coincident = new SimulationConfig(
                "重合",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 1.0e30, Vector3.ZERO, Vector3.ZERO),
                        new BodySpec("b", "乙", "#4d96ff", 1.0e30, Vector3.ZERO, Vector3.ZERO)),
                3600.0, PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0e7, 10_000L, null);
        assertTrue(ConfigValidator.validate(coincident).issues().stream()
                .anyMatch(i -> i.code() == ValidationCode.COINCIDENT_BODIES
                        && i.severity() == ValidationSeverity.ERROR));

        SimulationConfig noEnd = new SimulationConfig(
                "无结束条件", base.bodies(), 3600.0,
                PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0e7, null, null);
        assertTrue(ConfigValidator.validate(noEnd).issues().stream()
                .anyMatch(i -> i.code() == ValidationCode.MISSING_END_CONDITION));

        SimulationConfig badSteps = new SimulationConfig(
                "超上限步数", base.bodies(), 3600.0,
                PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0e7,
                ConfigValidator.MAX_ALLOWED_STEPS + 1, null);
        assertTrue(ConfigValidator.validate(badSteps).issues().stream()
                .anyMatch(i -> i.code() == ValidationCode.MAX_STEPS_OUT_OF_RANGE));

        SimulationConfig nanStep = new SimulationConfig(
                "NaN 步长", base.bodies(), Double.NaN,
                PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0e7, 10_000L, null);
        assertTrue(ConfigValidator.validate(nanStep).issues().stream()
                .anyMatch(i -> i.code() == ValidationCode.INVALID_TIME_STEP));
    }

    @Test
    @DisplayName("TIME_STEP_TOO_LARGE 在周期过短时给出 HIGH")
    void timeStepTooLargeHigh() {
        // 两体距离 1e8 m，近轨道周期很小，3600s 步长相对过大
        SimulationConfig config = new SimulationConfig(
                "大步长",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 1.0e30, Vector3.ZERO, Vector3.ZERO),
                        new BodySpec("b", "乙", "#4d96ff", 1.0e30, Vector3.of(1.0e8, 0, 0), Vector3.ZERO)),
                3600.0, PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0e6, 10_000L, null);
        ValidationResult result = ConfigValidator.validate(config);
        assertTrue(result.valid());
        ValidationIssue issue = result.issues().stream()
                .filter(i -> i.code() == ValidationCode.TIME_STEP_TOO_LARGE).findFirst().orElse(null);
        assertNotNull(issue, "应产生 TIME_STEP_TOO_LARGE 风险");
        assertEquals(ValidationSeverity.WARNING, issue.severity());
        assertEquals(RiskLevel.HIGH, issue.riskLevel());
    }

    @Test
    @DisplayName("初始距离小于近遇阈值给出 INITIAL_DISTANCE_TOO_SMALL/HIGH")
    void initialDistanceTooSmallHigh() {
        double eps = 1.0e7;
        SimulationConfig config = new SimulationConfig(
                "初始近遇",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 1.0e30, Vector3.ZERO, Vector3.ZERO),
                        new BodySpec("b", "乙", "#4d96ff", 1.0e30,
                                Vector3.of(3.0e7, 0, 0), Vector3.ZERO)),
                3600.0, PhysicalConstants.GRAVITATIONAL_CONSTANT, eps, 10_000L, null);
        ValidationResult result = ConfigValidator.validate(config);
        assertTrue(result.valid());
        ValidationIssue issue = result.issues().stream()
                .filter(i -> i.code() == ValidationCode.INITIAL_DISTANCE_TOO_SMALL).findFirst().orElse(null);
        assertNotNull(issue, "应产生 INITIAL_DISTANCE_TOO_SMALL 风险");
        assertEquals(RiskLevel.HIGH, issue.riskLevel());
        assertTrue(issue.message().contains("启动即进入"), "不应宣称一定碰撞");
    }

    @Test
    @DisplayName("相对速度高于逃逸速度给出 INITIAL_SPEED_HIGH")
    void initialSpeedHigh() {
        SimulationConfig config = new SimulationConfig(
                "高速初速度",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 1.0e30, Vector3.ZERO, Vector3.ZERO),
                        new BodySpec("b", "乙", "#4d96ff", 1.0e30,
                                Vector3.of(1.0e11, 0, 0), Vector3.of(3.0e5, 0, 0))),
                3600.0, PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0e7, 10_000L, null);
        ValidationResult result = ConfigValidator.validate(config);
        ValidationIssue issue = result.issues().stream()
                .filter(i -> i.code() == ValidationCode.INITIAL_SPEED_HIGH).findFirst().orElse(null);
        assertNotNull(issue);
        assertEquals(RiskLevel.HIGH, issue.riskLevel());
    }

    @Test
    @DisplayName("软化长度为 0 给出 SOFTENING_TOO_SMALL 且说明近遇阈值为 0")
    void zeroSofteningTooSmall() {
        SimulationConfig config = new SimulationConfig(
                "零软化",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 1.0e30, Vector3.ZERO, Vector3.ZERO),
                        new BodySpec("b", "乙", "#4d96ff", 1.0e30, Vector3.of(1.0e11, 0, 0), Vector3.ZERO)),
                3600.0, PhysicalConstants.GRAVITATIONAL_CONSTANT, 0.0, 10_000L, null);
        ValidationResult result = ConfigValidator.validate(config);
        assertTrue(result.valid());
        ValidationIssue issue = result.issues().stream()
                .filter(i -> i.code() == ValidationCode.SOFTENING_TOO_SMALL).findFirst().orElse(null);
        assertNotNull(issue);
        assertTrue(issue.message().contains("近遇阈值为 0"), "应说明近遇阈值为 0");
    }

    @Test
    @DisplayName("软化长度过大给出 SOFTENING_TOO_LARGE/HIGH")
    void softeningTooLarge() {
        SimulationConfig config = new SimulationConfig(
                "过大软化",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 1.0e30, Vector3.ZERO, Vector3.ZERO),
                        new BodySpec("b", "乙", "#4d96ff", 1.0e30, Vector3.of(1.0e11, 0, 0), Vector3.ZERO)),
                3600.0, PhysicalConstants.GRAVITATIONAL_CONSTANT, 2.0e10, 10_000L, null);
        ValidationResult result = ConfigValidator.validate(config);
        assertTrue(result.valid());
        ValidationIssue issue = result.issues().stream()
                .filter(i -> i.code() == ValidationCode.SOFTENING_TOO_LARGE).findFirst().orElse(null);
        assertNotNull(issue);
        assertEquals(RiskLevel.HIGH, issue.riskLevel());
    }

    @Test
    @DisplayName("同风险码只返回最严重的一条，不产生重复 Warning")
    void onlyMostSeverePairPerCode() {
        // 三体，其中一对极近会触发 INITIAL_DISTANCE_TOO_SMALL，另一对更远不触发
        SimulationConfig config = new SimulationConfig(
                "多体最近对",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 1.0e30, Vector3.ZERO, Vector3.ZERO),
                        new BodySpec("b", "乙", "#4d96ff", 1.0e30,
                                Vector3.of(2.0e7, 0, 0), Vector3.ZERO),
                        new BodySpec("c", "丙", "#06d6a0", 1.0e30,
                                Vector3.of(1.0e11, 0, 0), Vector3.ZERO)),
                3600.0, PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0e7, 10_000L, null);
        ValidationResult result = ConfigValidator.validate(config);
        long count = result.issues().stream()
                .filter(i -> i.code() == ValidationCode.INITIAL_DISTANCE_TOO_SMALL).count();
        assertEquals(1, count, "同一风险码只应返回最严重的一条");
    }

    @Test
    @DisplayName("预计步数取两个结束条件中先达到者")
    void estimatedStepsTakesEarliestEnd() {
        SimulationConfig both = new SimulationConfig(
                "双结束条件", validConfig().bodies(), 1000.0,
                PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0e7, 100L, 1.0e6);
        // ceil(1e6 / 1000) = 1000；与 maxSteps=100 取较小者
        assertEquals(100L, both.estimatedTotalSteps());

        SimulationConfig targetOnly = new SimulationConfig(
                "仅目标时间", validConfig().bodies(), 1000.0,
                PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0e7, null, 1.0e6);
        assertEquals(1000L, targetOnly.estimatedTotalSteps());

        SimulationConfig stepsOnly = new SimulationConfig(
                "仅最大步数", validConfig().bodies(), 1000.0,
                PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0e7, 100L, null);
        assertEquals(100L, stepsOnly.estimatedTotalSteps());

        SimulationConfig none = new SimulationConfig(
                "无结束条件", validConfig().bodies(), 1000.0,
                PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0e7, null, null);
        assertNull(none.estimatedTotalSteps());
    }

    @Test
    @DisplayName("NaN 与极端量级在风险计算中仍保持有限诊断")
    void extremeMagnitudesStayFinite() {
        SimulationConfig config = new SimulationConfig(
                "极端质量",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 1.0e300, Vector3.ZERO, Vector3.ZERO),
                        new BodySpec("b", "乙", "#4d96ff", 1.0e300, Vector3.of(1.0e200, 0, 0), Vector3.ZERO)),
                3600.0, PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0e7, 10_000L, null);
        ValidationResult result = ConfigValidator.validate(config);
        assertTrue(result.valid());
        // 极端量级不应产生非有限值或抛异常
        for (ValidationIssue issue : result.issues()) {
            assertFalse(issue.message().contains("NaN"));
        }
    }
}
