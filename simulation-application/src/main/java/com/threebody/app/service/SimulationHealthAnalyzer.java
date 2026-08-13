package com.threebody.app.service;

import com.threebody.app.domain.DriftTrend;
import com.threebody.app.domain.EventPhase;
import com.threebody.app.domain.HealthCloseEncounter;
import com.threebody.app.domain.HealthConfigPatch;
import com.threebody.app.domain.HealthFailure;
import com.threebody.app.domain.HealthReason;
import com.threebody.app.domain.HealthReasonSeverity;
import com.threebody.app.domain.HealthRecommendation;
import com.threebody.app.domain.HealthRecommendationAction;
import com.threebody.app.domain.HealthThresholds;
import com.threebody.app.domain.SimulationEvent;
import com.threebody.app.domain.SimulationHealthMetrics;
import com.threebody.app.domain.SimulationHealthReport;
import com.threebody.app.domain.SimulationHealthStatus;
import com.threebody.core.Metrics;
import com.threebody.core.MetricsCalculator;
import com.threebody.core.NumericalInstabilityException;
import com.threebody.core.SimulationConfig;
import com.threebody.core.SimulationState;
import com.threebody.core.Vector3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Run 级数值健康分析器.实例只由 ExperimentService 的单 worker 访问,
 * 每次采样以 O(1) 方式更新峰值和固定长度趋势窗口.
 */
public final class SimulationHealthAnalyzer {

    private static final int TREND_WINDOW_SIZE = 8;
    private static final long MAX_SAMPLES = 4096L;

    private final SimulationConfig config;
    private final HealthThresholds thresholds;
    private final double initialEnergyJoules;
    private final double energyReferenceScale;
    private final Vector3 initialAngularMomentum;
    private final double angularMomentumReferenceScale;
    private final long sampleStride;
    private final Deque<Double> energyWindow = new ArrayDeque<>();
    private final Deque<Double> angularWindow = new ArrayDeque<>();

    private SimulationHealthReport report;
    private long analysisIndex;
    private long lastEncounterAnalysisIndex = Long.MIN_VALUE;
    private boolean encounterDriftLinked;

    public SimulationHealthAnalyzer(SimulationConfig config, SimulationState initialState,
            SimulationHealthReport previous) {
        this(config, initialState, previous, HealthThresholds.defaults());
    }

    public SimulationHealthAnalyzer(SimulationConfig config, SimulationState initialState,
            SimulationHealthReport previous, HealthThresholds thresholds) {
        this.config = config;
        this.thresholds = thresholds;
        this.initialEnergyJoules = MetricsCalculator.totalEnergy(config, initialState);
        double kinetic0 = MetricsCalculator.kineticEnergy(config, initialState);
        double potential0 = MetricsCalculator.potentialEnergy(config, initialState);
        this.energyReferenceScale = Math.max(Math.abs(kinetic0) + Math.abs(potential0),
                Math.max(Math.abs(initialEnergyJoules), Double.MIN_NORMAL));
        this.initialAngularMomentum = MetricsCalculator.angularMomentum(config, initialState);
        this.angularMomentumReferenceScale = MetricsCalculator.angularMomentumReferenceScale(config, initialState);
        Long estimatedSteps = config.estimatedTotalSteps();
        this.sampleStride = estimatedSteps == null ? 1L
                : Math.max(1L, 1L + (estimatedSteps - 1L) / MAX_SAMPLES);
        this.report = previous;
        this.analysisIndex = previous != null ? previous.sampleCount() : 0L;
        this.encounterDriftLinked = previous != null && previous.reasons().stream()
                .anyMatch(reason -> "CLOSE_ENCOUNTER_NEAR_DRIFT".equals(reason.code()));
    }

    public boolean shouldSample(long step) {
        return step == 0L || step % sampleStride == 0L;
    }

    public long sampleStride() {
        return sampleStride;
    }

    public SimulationHealthReport report() {
        return report;
    }

    /** 近遇 ENTER 计数一次,UPDATE/FINAL 只更新本次真实最近点. */
    public void observeEncounter(SimulationEvent event) {
        if (event == null || event.type() != com.threebody.app.domain.SimulationEventType.NEAR_ENCOUNTER) {
            return;
        }
        lastEncounterAnalysisIndex = analysisIndex;
        SimulationHealthMetrics previous = report != null ? report.metrics() : null;
        long count = previous != null ? previous.closeEncounterCount() : 0L;
        if (event.phase() == EventPhase.ENTER) {
            count++;
        }
        double distance = event.closestDistanceMeters() != null
                ? event.closestDistanceMeters()
                : event.distanceMeters() != null ? event.distanceMeters() : Double.NaN;
        Double closest = previous != null ? previous.closestApproachMeters() : null;
        if (Double.isFinite(distance)) {
            closest = closest == null ? distance : Math.min(closest, distance);
        }
        HealthCloseEncounter latest = Double.isFinite(distance)
                ? new HealthCloseEncounter(event.eventId(), event.bodyIds(), distance,
                        event.closestStep() != null ? event.closestStep() : event.step(),
                        event.closestSimulationTimeSeconds() != null
                                ? event.closestSimulationTimeSeconds() : event.simulationTimeSeconds())
                : previous != null ? previous.latestCloseEncounter() : null;
        if (report != null) {
            SimulationHealthMetrics metrics = copyEncounter(previous, closest, count, latest);
            List<HealthReason> reasons = report.status() == SimulationHealthStatus.FAILED
                    ? report.reasons() : buildReasons(report.status(), metrics);
            List<HealthRecommendation> recommendations = report.status() == SimulationHealthStatus.FAILED
                    ? report.recommendations() : buildRecommendations(report.status());
            report = new SimulationHealthReport(report.status(), metrics, report.thresholds(),
                    reasons, recommendations,
                    report.failure(), report.analyzedStep(), report.analyzedSimulationTimeSeconds(),
                    report.sampleStride(), report.sampleCount());
        }
    }

    public SimulationHealthReport analyze(SimulationState state, Metrics rawMetrics, boolean hasActiveEncounter) {
        if (report != null && report.status() == SimulationHealthStatus.FAILED) {
            return report;
        }
        analysisIndex++;
        HealthFailure invalid = validateMetrics(state, rawMetrics);
        if (invalid != null) {
            return fail(invalid);
        }

        double energyDrift = Math.abs(rawMetrics.totalEnergyJoules() - initialEnergyJoules)
                / energyReferenceScale;
        Double angularDrift = null;
        if (Double.isFinite(angularMomentumReferenceScale) && angularMomentumReferenceScale > 0.0) {
            angularDrift = rawMetrics.angularMomentum().subtract(initialAngularMomentum).length()
                    / angularMomentumReferenceScale;
        }
        if (!Double.isFinite(energyDrift) || angularDrift != null && !Double.isFinite(angularDrift)) {
            return fail(new HealthFailure("NON_FINITE_METRICS", null,
                    angularDrift != null && !Double.isFinite(angularDrift)
                            ? "metrics.angularMomentumDrift" : "metrics.energyDrift",
                    state.step(), state.simulationTimeSeconds(), "NaN",
                    "A non-finite derived simulation metric was detected."));
        }

        addWindowValue(energyWindow, energyDrift);
        if (angularDrift != null) addWindowValue(angularWindow, angularDrift);

        SimulationHealthMetrics old = report != null ? report.metrics() : null;
        double peakEnergy = old != null ? old.peakEnergyDrift() : energyDrift;
        long peakEnergyStep = old != null ? old.peakEnergyDriftStep() : state.step();
        double peakEnergyTime = old != null
                ? old.peakEnergyDriftSimulationTimeSeconds() : state.simulationTimeSeconds();
        if (energyDrift > peakEnergy) {
            peakEnergy = energyDrift;
            peakEnergyStep = state.step();
            peakEnergyTime = state.simulationTimeSeconds();
        }

        Double peakAngular = old != null ? old.peakAngularMomentumDrift() : angularDrift;
        Long peakAngularStep = old != null ? old.peakAngularMomentumDriftStep()
                : angularDrift != null ? state.step() : null;
        Double peakAngularTime = old != null ? old.peakAngularMomentumDriftSimulationTimeSeconds()
                : angularDrift != null ? state.simulationTimeSeconds() : null;
        if (angularDrift != null && (peakAngular == null || angularDrift > peakAngular)) {
            peakAngular = angularDrift;
            peakAngularStep = state.step();
            peakAngularTime = state.simulationTimeSeconds();
        }

        Double closest = old != null ? old.closestApproachMeters() : null;
        if (Double.isFinite(rawMetrics.minimumPairDistanceMeters())) {
            closest = closest == null ? rawMetrics.minimumPairDistanceMeters()
                    : Math.min(closest, rawMetrics.minimumPairDistanceMeters());
        }
        long encounterCount = old != null ? old.closeEncounterCount() : 0L;
        HealthCloseEncounter latest = old != null ? old.latestCloseEncounter() : null;

        SimulationHealthMetrics metrics = new SimulationHealthMetrics(
                energyDrift, peakEnergy, peakEnergyStep, peakEnergyTime,
                angularDrift, peakAngular, peakAngularStep, peakAngularTime,
                trend(energyWindow), trend(angularWindow), closest, encounterCount, latest);
        SimulationHealthStatus oldStatus = report != null ? report.status() : SimulationHealthStatus.GOOD;
        SimulationHealthStatus status = statusFor(metrics);
        boolean crossed = rank(status) > rank(oldStatus) && status != SimulationHealthStatus.FAILED;
        boolean recentEncounter = lastEncounterAnalysisIndex != Long.MIN_VALUE
                && analysisIndex - lastEncounterAnalysisIndex <= 2L;
        if (crossed && (hasActiveEncounter || recentEncounter)) {
            encounterDriftLinked = true;
        }
        report = new SimulationHealthReport(status, metrics, thresholds,
                buildReasons(status, metrics), buildRecommendations(status), null,
                state.step(), state.simulationTimeSeconds(), sampleStride, analysisIndex);
        return report;
    }

    public SimulationHealthReport fail(NumericalInstabilityException failure,
            SimulationState lastStableState) {
        long step = failure.getStep() >= 0 ? failure.getStep() : lastStableState.step();
        double time = Double.isFinite(failure.getSimulationTimeSeconds())
                ? failure.getSimulationTimeSeconds() : lastStableState.simulationTimeSeconds();
        return fail(new HealthFailure("NON_FINITE_STATE", failure.getBodyId(), failure.getField(),
                step, time, failure.getValue(), failure.getMessage()));
    }

    public SimulationHealthReport fail(HealthFailure failure) {
        SimulationHealthMetrics metrics = report != null ? report.metrics() : emptyMetrics();
        List<HealthReason> reasons = new ArrayList<>(buildReasons(SimulationHealthStatus.FAILED, metrics));
        if (reasons.stream().noneMatch(reason -> failure.code().equals(reason.code()))) {
            reasons.add(new HealthReason(failure.code(), HealthReasonSeverity.ERROR,
                    failure.field(), "NON_FINITE_METRICS".equals(failure.code())
                            ? "A non-finite derived simulation metric was detected."
                            : "A non-finite simulation state was detected."));
        }
        report = new SimulationHealthReport(SimulationHealthStatus.FAILED, metrics, thresholds,
                reasons, buildRecommendations(SimulationHealthStatus.FAILED), failure,
                failure.step(), failure.simulationTimeSeconds(), sampleStride,
                Math.max(analysisIndex, report != null ? report.sampleCount() : 0L));
        return report;
    }

    private HealthFailure validateMetrics(SimulationState state, Metrics metrics) {
        if (metrics == null) {
            return new HealthFailure("NON_FINITE_METRICS", null, "metrics", state.step(),
                    state.simulationTimeSeconds(), null, "Simulation metrics are unavailable.");
        }
        if (!Double.isFinite(metrics.kineticEnergyJoules())) {
            return metricFailure(state, "metrics.kineticEnergyJoules", metrics.kineticEnergyJoules());
        }
        if (!Double.isFinite(metrics.potentialEnergyJoules())) {
            return metricFailure(state, "metrics.potentialEnergyJoules", metrics.potentialEnergyJoules());
        }
        if (!Double.isFinite(metrics.totalEnergyJoules())) {
            return metricFailure(state, "metrics.totalEnergyJoules", metrics.totalEnergyJoules());
        }
        if (!metrics.angularMomentum().isFinite()) {
            return new HealthFailure("NON_FINITE_METRICS", null, "metrics.angularMomentum",
                    state.step(), state.simulationTimeSeconds(), vectorValue(metrics.angularMomentum()),
                    "A non-finite angular momentum value was detected.");
        }
        if (!Double.isFinite(metrics.angularMomentumMagnitude())) {
            return metricFailure(state, "metrics.angularMomentumMagnitude",
                    metrics.angularMomentumMagnitude());
        }
        if (!metrics.linearMomentum().isFinite()) {
            return new HealthFailure("NON_FINITE_METRICS", null, "metrics.linearMomentum",
                    state.step(), state.simulationTimeSeconds(), vectorValue(metrics.linearMomentum()),
                    "A non-finite linear momentum value was detected.");
        }
        if (!Double.isFinite(metrics.linearMomentumMagnitude())) {
            return metricFailure(state, "metrics.linearMomentumMagnitude",
                    metrics.linearMomentumMagnitude());
        }
        if (!Double.isFinite(metrics.relativeEnergyDrift())) {
            return metricFailure(state, "metrics.relativeEnergyDrift", metrics.relativeEnergyDrift());
        }
        if (!Double.isFinite(metrics.minimumPairDistanceMeters())) {
            return metricFailure(state, "metrics.minimumPairDistanceMeters",
                    metrics.minimumPairDistanceMeters());
        }
        return null;
    }

    private HealthFailure metricFailure(SimulationState state, String field, double value) {
        return new HealthFailure("NON_FINITE_METRICS", null, field, state.step(),
                state.simulationTimeSeconds(), valueText(value),
                "A non-finite derived simulation metric was detected.");
    }

    private static String vectorValue(Vector3 vector) {
        return "[" + valueText(vector.x()) + "," + valueText(vector.y()) + ","
                + valueText(vector.z()) + "]";
    }

    private SimulationHealthStatus statusFor(SimulationHealthMetrics metrics) {
        double angularPeak = metrics.peakAngularMomentumDrift() != null
                ? metrics.peakAngularMomentumDrift() : 0.0;
        if (metrics.peakEnergyDrift() >= thresholds.energyPoor()
                || angularPeak >= thresholds.angularMomentumPoor()) return SimulationHealthStatus.POOR;
        if (metrics.peakEnergyDrift() >= thresholds.energyWarning()
                || angularPeak >= thresholds.angularMomentumWarning()) return SimulationHealthStatus.WARNING;
        return SimulationHealthStatus.GOOD;
    }

    private List<HealthReason> buildReasons(SimulationHealthStatus status,
            SimulationHealthMetrics metrics) {
        List<HealthReason> reasons = new ArrayList<>();
        addDriftReason(reasons, "ENERGY_DRIFT", "energy",
                metrics.peakEnergyDrift(), thresholds.energyWarning(), thresholds.energyPoor());
        if (metrics.peakAngularMomentumDrift() != null) {
            addDriftReason(reasons, "ANGULAR_MOMENTUM_DRIFT", "angularMomentum",
                    metrics.peakAngularMomentumDrift(), thresholds.angularMomentumWarning(),
                    thresholds.angularMomentumPoor());
        }
        if (encounterDriftLinked) {
            reasons.add(new HealthReason("CLOSE_ENCOUNTER_NEAR_DRIFT", HealthReasonSeverity.WARNING,
                    null, "A close encounter occurred near the onset of conservation drift; the fixed RK4 step may be too large for the rapidly changing motion."));
        }
        if (metrics.closestApproachMeters() != null && config.softeningLengthMeters() > 0.0
                && metrics.closestApproachMeters() <= 5.0 * config.softeningLengthMeters()) {
            reasons.add(new HealthReason("SOFTENING_SCALE_APPROACHED", HealthReasonSeverity.INFO,
                    "closestApproach", "The closest encounter approached the configured softening scale; softening may significantly influence this interaction."));
        }
        if (status == SimulationHealthStatus.GOOD && reasons.isEmpty()) {
            reasons.add(new HealthReason("CONSERVATION_WITHIN_TOLERANCE", HealthReasonSeverity.INFO,
                    null, "Sampled conservation drift remains within the configured project tolerance."));
        }
        return reasons;
    }

    private void addDriftReason(List<HealthReason> reasons, String prefix, String metric,
            double peak, double warning, double poor) {
        if (peak >= poor) {
            reasons.add(new HealthReason(prefix + "_HIGH", HealthReasonSeverity.ERROR, metric,
                    metric + " drift exceeded the configured poor tolerance."));
        } else if (peak >= warning) {
            reasons.add(new HealthReason(prefix + "_ELEVATED", HealthReasonSeverity.WARNING, metric,
                    metric + " drift exceeded the configured warning tolerance."));
        }
    }

    private List<HealthRecommendation> buildRecommendations(SimulationHealthStatus status) {
        if (status == SimulationHealthStatus.GOOD) return List.of();
        return List.of(new HealthRecommendation("REDUCE_TIME_STEP",
                HealthRecommendationAction.CLONE_AND_RETRY,
                "创建一个时间步长更小的对照实验。",
                new HealthConfigPatch(config.timeStepSeconds() / 10.0),
                "只改变数值分辨率，有助于判断当前漂移或失败是否由时间步长过大引起。",
                "时间步长缩小为十分之一时，为保持相同模拟时长，积分步数约增加到十倍。",
                "比较源运行记录与对照实验的峰值漂移和最终数值健康；改善说明时间分辨率是重要因素。"));
    }

    private SimulationHealthMetrics copyEncounter(SimulationHealthMetrics metrics, Double closest,
            long count, HealthCloseEncounter latest) {
        return new SimulationHealthMetrics(metrics.currentEnergyDrift(), metrics.peakEnergyDrift(),
                metrics.peakEnergyDriftStep(), metrics.peakEnergyDriftSimulationTimeSeconds(),
                metrics.currentAngularMomentumDrift(), metrics.peakAngularMomentumDrift(),
                metrics.peakAngularMomentumDriftStep(), metrics.peakAngularMomentumDriftSimulationTimeSeconds(),
                metrics.energyTrend(), metrics.angularMomentumTrend(), closest, count, latest);
    }

    private SimulationHealthMetrics emptyMetrics() {
        return new SimulationHealthMetrics(0.0, 0.0, 0L, 0.0,
                null, null, null, null, DriftTrend.STABLE, DriftTrend.STABLE,
                null, 0L, null);
    }

    private static void addWindowValue(Deque<Double> window, double value) {
        if (!Double.isFinite(value)) return;
        if (window.size() == TREND_WINDOW_SIZE) window.removeFirst();
        window.addLast(value);
    }

    private static DriftTrend trend(Deque<Double> window) {
        if (window.size() < 4) return DriftTrend.STABLE;
        List<Double> values = List.copyOf(window);
        double first = (values.get(0) + values.get(1)) / 2.0;
        double last = (values.get(values.size() - 1) + values.get(values.size() - 2)) / 2.0;
        int increases = 0;
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) > values.get(i - 1)) increases++;
        }
        double increaseRatio = (double) increases / (values.size() - 1);
        double floor = Math.max(first, 1e-15);
        if (last > floor * 4.0 && increaseRatio >= 0.7) return DriftTrend.RAPIDLY_INCREASING;
        if (last > floor * 1.2 && increaseRatio >= 0.6) return DriftTrend.SLOWLY_INCREASING;
        return DriftTrend.STABLE;
    }

    private static int rank(SimulationHealthStatus status) {
        return switch (status) {
            case GOOD -> 0;
            case WARNING -> 1;
            case POOR -> 2;
            case FAILED -> 3;
        };
    }

    private static String valueText(double value) {
        if (Double.isNaN(value)) return "NaN";
        return value > 0.0 ? "Infinity" : "-Infinity";
    }
}
