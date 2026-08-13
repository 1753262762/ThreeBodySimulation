package com.threebody.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.threebody.app.domain.DriftTrend;
import com.threebody.app.domain.HealthFailure;
import com.threebody.app.domain.HealthThresholds;
import com.threebody.app.domain.EventPhase;
import com.threebody.app.domain.SimulationEvent;
import com.threebody.app.domain.SimulationEventType;
import com.threebody.app.domain.SimulationHealthReport;
import com.threebody.app.domain.SimulationHealthStatus;
import com.threebody.core.BodySpec;
import com.threebody.core.Metrics;
import com.threebody.core.MetricsCalculator;
import com.threebody.core.NBodyIntegrator;
import com.threebody.core.PhysicalConstants;
import com.threebody.core.SimulationConfig;
import com.threebody.core.SimulationState;
import com.threebody.core.Vector3;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimulationHealthAnalyzerTest {

    private static SimulationConfig config() {
        return new SimulationConfig("health", List.of(
                new BodySpec("a", "A", "#fff", 1e24,
                        Vector3.of(-1e7, 0, 0), Vector3.of(0, -1000, 0)),
                new BodySpec("b", "B", "#000", 1e24,
                        Vector3.of(1e7, 0, 0), Vector3.of(0, 1000, 0))),
                500.0, PhysicalConstants.GRAVITATIONAL_CONSTANT, 5e6, 100L, null);
    }

    private static Metrics metrics(SimulationConfig config, SimulationState initial,
            double energyDrift, Vector3 angularMomentum) {
        double e0 = MetricsCalculator.totalEnergy(config, initial);
        double scale = Math.max(
                Math.abs(MetricsCalculator.kineticEnergy(config, initial))
                        + Math.abs(MetricsCalculator.potentialEnergy(config, initial)),
                Math.max(Math.abs(e0), Double.MIN_NORMAL));
        return new Metrics(1.0, -1.0, e0 + energyDrift * scale, e0, energyDrift,
                angularMomentum, Vector3.ZERO, 2e7, List.of("a", "b"));
    }

    @Test
    void thresholdsUseSampledPeakAndDoNotRecover() {
        SimulationConfig config = config();
        SimulationState initial = NBodyIntegrator.initialState(config);
        Vector3 angular = MetricsCalculator.angularMomentum(config, initial);
        SimulationHealthAnalyzer analyzer = new SimulationHealthAnalyzer(config, initial, null);

        assertEquals(SimulationHealthStatus.GOOD,
                analyzer.analyze(initial, metrics(config, initial, 0.000999, angular), false).status());
        SimulationState warningState = new SimulationState(1, 500.0, initial.bodies());
        assertEquals(SimulationHealthStatus.WARNING,
                analyzer.analyze(warningState, metrics(config, initial, 0.001, angular), false).status());
        assertTrue(analyzer.report().reasons().stream()
                .noneMatch(reason -> "CLOSE_ENCOUNTER_NEAR_DRIFT".equals(reason.code())));
        SimulationState recoveredState = new SimulationState(2, 1000.0, initial.bodies());
        SimulationHealthReport recovered = analyzer.analyze(recoveredState,
                metrics(config, initial, 0.0001, angular), false);
        assertEquals(SimulationHealthStatus.WARNING, recovered.status());
        assertEquals(0.001, recovered.metrics().peakEnergyDrift(), 1e-15);

        SimulationState poorState = new SimulationState(3, 1500.0, initial.bodies());
        assertEquals(SimulationHealthStatus.POOR,
                analyzer.analyze(poorState, metrics(config, initial, 0.010001, angular), false).status());
        assertEquals(50.0, analyzer.report().recommendations().get(0).configPatch().timeStepSeconds());
    }

    @Test
    void angularMomentumUsesVectorDifferenceInsteadOfMagnitudeDifference() {
        SimulationConfig config = config();
        SimulationState initial = NBodyIntegrator.initialState(config);
        Vector3 angular = MetricsCalculator.angularMomentum(config, initial);
        SimulationHealthAnalyzer analyzer = new SimulationHealthAnalyzer(config, initial, null);

        SimulationHealthReport report = analyzer.analyze(initial,
                metrics(config, initial, 0.0, angular.multiply(-1.0)), false);
        assertEquals(SimulationHealthStatus.POOR, report.status());
        assertTrue(report.metrics().currentAngularMomentumDrift() > 1.0);
    }

    @Test
    void zeroAngularMomentumBaselineProducesUnavailableDrift() {
        SimulationConfig zero = new SimulationConfig("zero", List.of(
                new BodySpec("a", "A", "#fff", 1e24, Vector3.of(-1, 0, 0), Vector3.ZERO),
                new BodySpec("b", "B", "#000", 1e24, Vector3.of(1, 0, 0), Vector3.ZERO)),
                1.0, PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0, 10L, null);
        SimulationState initial = NBodyIntegrator.initialState(zero);
        SimulationHealthAnalyzer analyzer = new SimulationHealthAnalyzer(zero, initial, null);
        Metrics raw = MetricsCalculator.compute(zero, initial,
                MetricsCalculator.totalEnergy(zero, initial));

        assertNull(analyzer.analyze(initial, raw, false).metrics().currentAngularMomentumDrift());
    }

    @Test
    void cancellingAngularMomentumUsesSumOfContributionMagnitudesAsReference() {
        SimulationConfig cancelling = new SimulationConfig("cancelling", List.of(
                new BodySpec("a", "A", "#fff", 1.0, Vector3.of(1, 0, 0), Vector3.of(0, 1, 0)),
                new BodySpec("b", "B", "#000", 1.0, Vector3.of(-1, 0, 0), Vector3.of(0, 1, 0))),
                1.0, PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0, 10L, null);
        SimulationState initial = NBodyIntegrator.initialState(cancelling);
        SimulationHealthAnalyzer analyzer = new SimulationHealthAnalyzer(cancelling, initial, null);
        SimulationHealthReport report = analyzer.analyze(initial,
                metrics(cancelling, initial, 0.0, Vector3.of(0, 0, 0.002)), false);

        assertNotNull(report.metrics().currentAngularMomentumDrift());
        assertEquals(0.001, report.metrics().currentAngularMomentumDrift(), 1e-15);
    }

    @Test
    void nonFiniteDerivedMetricsProduceStructuredFailure() {
        SimulationConfig config = config();
        SimulationState initial = NBodyIntegrator.initialState(config);
        Metrics invalid = new Metrics(Double.POSITIVE_INFINITY, -1.0, 1.0, 1.0, 0.0,
                Vector3.ZERO, Vector3.ZERO, 2e7, List.of("a", "b"));
        SimulationHealthReport report = new SimulationHealthAnalyzer(config, initial, null)
                .analyze(initial, invalid, false);

        assertEquals(SimulationHealthStatus.FAILED, report.status());
        assertEquals("NON_FINITE_METRICS", report.failure().code());
        assertEquals("metrics.kineticEnergyJoules", report.failure().field());
        assertEquals("Infinity", report.failure().value());
    }

    @Test
    void closeEncounterIsInformationalUntilDriftCrossesThresholdNearby() {
        SimulationConfig config = config();
        SimulationState initial = NBodyIntegrator.initialState(config);
        Vector3 angular = MetricsCalculator.angularMomentum(config, initial);
        SimulationHealthAnalyzer analyzer = new SimulationHealthAnalyzer(config, initial, null);
        analyzer.analyze(initial, metrics(config, initial, 0.0, angular), false);
        analyzer.observeEncounter(new SimulationEvent(1L, "encounter", SimulationEventType.NEAR_ENCOUNTER,
                EventPhase.ENTER, 0L, 0.0, Instant.EPOCH, "close", List.of("a", "b"),
                2e7, 2.5e7, 2e7, 2e7, 0L, 0.0, Vector3.ZERO, null));

        assertEquals(SimulationHealthStatus.GOOD, analyzer.report().status());
        assertEquals(1L, analyzer.report().metrics().closeEncounterCount());
        assertTrue(analyzer.report().reasons().stream()
                .anyMatch(reason -> "SOFTENING_SCALE_APPROACHED".equals(reason.code())));

        SimulationState next = new SimulationState(1L, 500.0, initial.bodies());
        SimulationHealthReport warning = analyzer.analyze(next,
                metrics(config, initial, 0.002, angular), true);
        assertEquals(SimulationHealthStatus.WARNING, warning.status());
        assertTrue(warning.reasons().stream()
                .anyMatch(reason -> "CLOSE_ENCOUNTER_NEAR_DRIFT".equals(reason.code())));
    }

    @Test
    void trendIsDisplayOnlyAndFailureIsSticky() {
        SimulationConfig config = config();
        SimulationState initial = NBodyIntegrator.initialState(config);
        Vector3 angular = MetricsCalculator.angularMomentum(config, initial);
        HealthThresholds loose = new HealthThresholds(1.0, 2.0, 1.0, 2.0);
        SimulationHealthAnalyzer analyzer = new SimulationHealthAnalyzer(config, initial, null, loose);
        double[] values = {1e-6, 2e-6, 4e-6, 16e-6};
        for (int i = 0; i < values.length; i++) {
            SimulationState state = new SimulationState(i, i * 500.0, initial.bodies());
            analyzer.analyze(state, metrics(config, initial, values[i], angular), false);
        }
        assertEquals(DriftTrend.RAPIDLY_INCREASING, analyzer.report().metrics().energyTrend());
        assertEquals(SimulationHealthStatus.GOOD, analyzer.report().status());

        HealthFailure failure = new HealthFailure("NON_FINITE_METRICS", "a", "velocity.x",
                5L, 2500.0, "Infinity", "non-finite value");
        SimulationHealthReport failed = analyzer.fail(failure);
        assertEquals(SimulationHealthStatus.FAILED, failed.status());
        assertNotNull(failed.failure());
        SimulationState later = new SimulationState(6, 3000.0, initial.bodies());
        assertEquals(failed, analyzer.analyze(later,
                metrics(config, initial, 0.0, angular), false));
    }

    @Test
    void realCloseEncounterFixtureDistinguishesCoarseAndFineTimeSteps() {
        SimulationHealthReport coarse = runSoftenedBinary(5000.0);
        SimulationHealthReport fine = runSoftenedBinary(500.0);

        assertEquals(SimulationHealthStatus.WARNING, coarse.status());
        assertTrue(coarse.metrics().peakEnergyDrift() >= 0.001);
        assertEquals(SimulationHealthStatus.GOOD, fine.status());
        assertTrue(fine.metrics().peakEnergyDrift() < 0.001);
        assertTrue(fine.metrics().peakAngularMomentumDrift() < 0.001);
    }

    private static SimulationHealthReport runSoftenedBinary(double timeStepSeconds) {
        double massKg = 1e24;
        double orbitalRadiusMeters = 1e7;
        double separationMeters = 2.0 * orbitalRadiusMeters;
        double softeningMeters = 5e6;
        double acceleration = PhysicalConstants.GRAVITATIONAL_CONSTANT * massKg * separationMeters
                / Math.pow(separationMeters * separationMeters + softeningMeters * softeningMeters, 1.5);
        double speedMetersPerSecond = Math.sqrt(acceleration * orbitalRadiusMeters);
        long steps = Math.round(50_000.0 / timeStepSeconds);
        SimulationConfig config = new SimulationConfig("softened binary", List.of(
                new BodySpec("a", "A", "#fff", massKg,
                        Vector3.of(-orbitalRadiusMeters, 0, 0), Vector3.of(0, -speedMetersPerSecond, 0)),
                new BodySpec("b", "B", "#000", massKg,
                        Vector3.of(orbitalRadiusMeters, 0, 0), Vector3.of(0, speedMetersPerSecond, 0))),
                timeStepSeconds, PhysicalConstants.GRAVITATIONAL_CONSTANT, softeningMeters, steps, null);
        SimulationState state = NBodyIntegrator.initialState(config);
        double initialEnergy = MetricsCalculator.totalEnergy(config, state);
        SimulationHealthAnalyzer analyzer = new SimulationHealthAnalyzer(config, state, null);
        analyzer.analyze(state, MetricsCalculator.compute(config, state, initialEnergy), true);
        for (long step = 0; step < steps; step++) {
            state = NBodyIntegrator.step(config, state).state();
            analyzer.analyze(state, MetricsCalculator.compute(config, state, initialEnergy), true);
        }
        return analyzer.report();
    }
}
