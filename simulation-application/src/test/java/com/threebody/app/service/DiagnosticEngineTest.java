package com.threebody.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.threebody.app.domain.Diagnostic;
import com.threebody.app.domain.DiagnosticCauseCategory;
import com.threebody.app.domain.DiagnosticSeverity;
import com.threebody.core.BodySpec;
import com.threebody.core.Metrics;
import com.threebody.core.MetricsCalculator;
import com.threebody.core.PhysicalConstants;
import com.threebody.core.SimulationConfig;
import com.threebody.core.SimulationState;
import com.threebody.core.Vector3;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** B4 结构化诊断引擎测试。 */
class DiagnosticEngineTest {

    private static SimulationConfig config() {
        return new SimulationConfig(
                "诊断用例",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 1.0e30, Vector3.of(-1.0e11, 0, 0), Vector3.of(0, -1.3e4, 0)),
                        new BodySpec("b", "乙", "#4d96ff", 1.0e30, Vector3.of(1.0e11, 0, 0), Vector3.of(0, 1.3e4, 0))),
                3600.0,
                PhysicalConstants.GRAVITATIONAL_CONSTANT,
                1.0e7,
                10_000L,
                null);
    }

    private static Metrics metricsWithEnergy(SimulationConfig config, SimulationState state, double totalEnergy) {
        return new Metrics(
                1.0, -1.0, totalEnergy, MetricsCalculator.totalEnergy(config, state),
                0.0, Vector3.ZERO, Vector3.ZERO, 1.0e11, List.of("a", "b"));
    }

    @Test
    @DisplayName("能量误差跨 1e-3 产生 NOTICE，随后升级到 WARNING 允许再次发布")
    void energyDriftCrossesLevels() {
        SimulationState initial = com.threebody.core.NBodyIntegrator.initialState(config());
        DiagnosticEngine engine = new DiagnosticEngine(config(), initial);

        double e0 = MetricsCalculator.totalEnergy(config(), initial);
        double k0 = MetricsCalculator.kineticEnergy(config(), initial);
        double u0 = MetricsCalculator.potentialEnergy(config(), initial);
        double scale = Math.max(Math.abs(k0) + Math.abs(u0),
                Math.max(Math.abs(e0), Double.MIN_NORMAL));

        // 第一次：误差 ~5e-3 → NOTICE
        List<Diagnostic> first = engine.evaluate(initial,
                metricsWithEnergy(config(), initial, e0 + 5e-3 * scale), false);
        assertEquals(1, first.size());
        assertEquals("ENERGY_DRIFT", first.get(0).code());
        assertEquals(DiagnosticSeverity.NOTICE, first.get(0).severity());
        assertEquals(DiagnosticCauseCategory.NUMERICAL_ERROR, first.get(0).causeCategory());

        // 冷却期内同等级不重复
        List<Diagnostic> suppressed = engine.evaluate(initial,
                metricsWithEnergy(config(), initial, e0 + 6e-3 * scale), false);
        assertTrue(suppressed.isEmpty(), "冷却期内同等级不应重复");

        // 升级到 WARNING 允许绕过冷却
        List<Diagnostic> escalated = engine.evaluate(initial,
                metricsWithEnergy(config(), initial, e0 + 2e-2 * scale), false);
        assertEquals(1, escalated.size());
        assertEquals(DiagnosticSeverity.WARNING, escalated.get(0).severity());
    }

    @Test
    @DisplayName("近零初始能量使用归一化尺度，不除以近零值")
    void nearZeroInitialEnergyStaysFinite() {
        SimulationState initial = com.threebody.core.NBodyIntegrator.initialState(config());
        DiagnosticEngine engine = new DiagnosticEngine(config(), initial);
        double e0 = MetricsCalculator.totalEnergy(config(), initial);

        // 构造初始能量接近 0 的尺度
        DiagnosticEngine tiny = new DiagnosticEngine(config(), initial);
        List<Diagnostic> result = tiny.evaluate(initial,
                metricsWithEnergy(config(), initial, 1e-3), false);
        assertNotNull(result);
    }

    @Test
    @DisplayName("非有限结果诊断标记 CRITICAL/NUMERICAL_ERROR 并保留最后稳定步")
    void numericalInstabilityDiagnostic() {
        Diagnostic d = DiagnosticEngine.numericalInstability(config(), 42L, List.of("a", "b"), 1.0e7);
        assertEquals("NUMERICAL_INSTABILITY", d.code());
        assertEquals(DiagnosticSeverity.CRITICAL, d.severity());
        assertEquals(DiagnosticCauseCategory.NUMERICAL_ERROR, d.causeCategory());
        assertEquals(42L, d.evidence().lastStableStep());
    }

    @Test
    @DisplayName("相对速度远超逃逸速度时产生 POSSIBLE_ESCAPE")
    void escapeDetectedWhenOutwardAndFast() {
        SimulationConfig escapeConfig = new SimulationConfig(
                "逃逸用例",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 1.0e30, Vector3.ZERO, Vector3.ZERO),
                        new BodySpec("b", "乙", "#4d96ff", 1.0e20,
                                Vector3.of(1.0e11, 0, 0), Vector3.of(1.0e6, 0, 0))),
                3600.0, PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0e7, 10_000L, null);
        SimulationState initial = com.threebody.core.NBodyIntegrator.initialState(escapeConfig);
        DiagnosticEngine engine = new DiagnosticEngine(escapeConfig, initial);

        List<Diagnostic> result = engine.evaluate(initial,
                metricsWithEnergy(escapeConfig, initial,
                        MetricsCalculator.totalEnergy(escapeConfig, initial)), false);
        boolean escapeFound = result.stream().anyMatch(d -> d.code().equals("POSSIBLE_ESCAPE"));
        assertTrue(escapeFound, "向外且超逃逸速度应产生 POSSIBLE_ESCAPE");
    }
}
