package com.threebody.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.threebody.app.domain.Diagnostic;
import com.threebody.app.domain.DiagnosticCauseCategory;
import com.threebody.app.domain.DiagnosticSeverity;
import com.threebody.core.BodySpec;
import com.threebody.core.Metrics;
import com.threebody.core.MetricsCalculator;
import com.threebody.core.NBodyIntegrator;
import com.threebody.core.PhysicalConstants;
import com.threebody.core.SimulationConfig;
import com.threebody.core.SimulationState;
import com.threebody.core.Vector3;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiagnosticEngineTest {

    private static Metrics metrics(SimulationConfig config, SimulationState state) {
        double totalEnergy = MetricsCalculator.totalEnergy(config, state);
        return new Metrics(1.0, -1.0, totalEnergy, totalEnergy, 0.0,
                Vector3.ZERO, Vector3.ZERO, 1.0e11, List.of("a", "b"));
    }

    @Test
    void numericalInstabilityDiagnosticRetainsLastStableStep() {
        SimulationConfig config = escapeConfig();
        Diagnostic diagnostic = DiagnosticEngine.numericalInstability(config, 42L,
                List.of("a", "b"), 1.0e7);
        assertEquals("NUMERICAL_INSTABILITY", diagnostic.code());
        assertEquals(DiagnosticSeverity.CRITICAL, diagnostic.severity());
        assertEquals(DiagnosticCauseCategory.NUMERICAL_ERROR, diagnostic.causeCategory());
        assertEquals(42L, diagnostic.evidence().lastStableStep());
    }

    @Test
    void physicalEscapeDiagnosticRemainsAvailableWithoutNumericalThresholds() {
        SimulationConfig config = escapeConfig();
        SimulationState initial = NBodyIntegrator.initialState(config);
        DiagnosticEngine engine = new DiagnosticEngine(config, initial);

        List<Diagnostic> result = engine.evaluate(initial, metrics(config, initial), false);
        assertTrue(result.stream().anyMatch(diagnostic -> "POSSIBLE_ESCAPE".equals(diagnostic.code())));
        assertTrue(result.stream().noneMatch(diagnostic -> "ENERGY_DRIFT".equals(diagnostic.code())));
    }

    private static SimulationConfig escapeConfig() {
        return new SimulationConfig("escape", List.of(
                new BodySpec("a", "A", "#ffd166", 1.0e30, Vector3.ZERO, Vector3.ZERO),
                new BodySpec("b", "B", "#4d96ff", 1.0e20,
                        Vector3.of(1.0e11, 0, 0), Vector3.of(1.0e6, 0, 0))),
                3600.0, PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0e7, 10_000L, null);
    }
}
