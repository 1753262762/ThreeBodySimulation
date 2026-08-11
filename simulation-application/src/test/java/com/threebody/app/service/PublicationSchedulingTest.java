package com.threebody.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.threebody.app.domain.Experiment;
import com.threebody.app.domain.ExperimentStatus;
import com.threebody.app.event.ExperimentMessage;
import com.threebody.app.event.ExperimentMessageType;
import com.threebody.app.service.persistence.FileExperimentRepository;
import com.threebody.core.BodySpec;
import com.threebody.core.PhysicalConstants;
import com.threebody.core.SimulationConfig;
import com.threebody.core.Vector3;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicationSchedulingTest {

    @TempDir
    Path tempDir;

    @Test
    void missedDeadlinesAdvanceWithoutBurstReplay() {
        long period = 1_000_000_000L;
        assertEquals(6_000_000_000L,
                ExperimentService.advanceDeadline(1_000_000_000L, 5_200_000_000L, period));
        assertEquals(6_000_000_000L,
                ExperimentService.advanceDeadline(1_000_000_000L, 5_999_999_999L, period));
        assertEquals(1_000_000_000L,
                ExperimentService.advanceDeadline(1_000_000_000L, 999_999_999L, period));
        assertEquals(60.0, ExperimentService.SNAPSHOT_HZ);
        assertEquals(60.0, ExperimentService.TRAJECTORY_HZ);
        assertEquals(2.0, ExperimentService.METRICS_HZ);
        assertEquals(ExperimentService.SNAPSHOT_PERIOD_NANOS,
                ExperimentService.TRAJECTORY_PERIOD_NANOS);
    }

    @Test
    void fastFiniteRunsAreSpreadAcrossVisibleSnapshotFrames() {
        try (ExperimentService service = new ExperimentService(
                new FileExperimentRepository(tempDir), System::nanoTime, true)) {
            assertEquals(84L, service.realtimeSnapshotStepBudget(config(20_000L)));
            assertEquals(1L, service.realtimeSnapshotStepBudget(config(200L)),
                    "short user runs should still expose each distinct state to the display");
        }
    }

    @Test
    void completionPublishesAuthoritativeSnapshotTrajectoryAndLastSequence() throws Exception {
        List<ExperimentMessage> messages = new CopyOnWriteArrayList<>();
            try (ExperimentService service = new ExperimentService(
                new FileExperimentRepository(tempDir), (MonotonicClock) System::nanoTime)) {
            service.addEventListener(messages::add);
            Experiment experiment = service.createExperiment("final", config(8L));
            assertTrue(await(() -> service.getExperiment(experiment.id()).status()
                    == ExperimentStatus.COMPLETED, 5_000L));

            Experiment completed = service.getExperiment(experiment.id());
            assertTrue(await(() -> messages.stream()
                    .filter(message -> message.type() == ExperimentMessageType.SNAPSHOT)
                    .map(ExperimentMessage::payload)
                    .filter(ExperimentService.SnapshotPayload.class::isInstance)
                    .map(ExperimentService.SnapshotPayload.class::cast)
                    .anyMatch(snapshot -> snapshot.step() == completed.step()), 2_000L));
            ExperimentService.SnapshotPayload finalSnapshot = messages.stream()
                    .filter(message -> message.type() == ExperimentMessageType.SNAPSHOT)
                    .map(ExperimentMessage::payload)
                    .filter(ExperimentService.SnapshotPayload.class::isInstance)
                    .map(ExperimentService.SnapshotPayload.class::cast)
                    .filter(snapshot -> snapshot.step() == completed.step())
                    .findFirst()
                    .orElse(null);
            assertTrue(finalSnapshot != null, "final state snapshot is required");
            assertTrue(messages.stream()
                    .filter(message -> message.type() == ExperimentMessageType.TRAJECTORY)
                    .map(ExperimentMessage::payload)
                    .filter(ExperimentService.TrajectoryPayload.class::isInstance)
                    .map(ExperimentService.TrajectoryPayload.class::cast)
                    .flatMap(payload -> payload.points().stream())
                    .anyMatch(point -> point.step() == completed.step()),
                    "final state trajectory point is required");
            assertTrue(completed.lastSequence() >= messages.stream()
                    .mapToLong(ExperimentMessage::sequence).max().orElse(0L));
        }
    }

    @Test
    void concurrentSequenceAllocationKeepsLastSequenceAtMaximum() throws Exception {
        try (ExperimentService service = new ExperimentService(new FileExperimentRepository(tempDir))) {
            Experiment experiment = new Experiment("sequence", "sequence", config(1L));
            Method allocator = ExperimentService.class.getDeclaredMethod("nextSequence", Experiment.class);
            allocator.setAccessible(true);
            int workers = 8;
            int perWorker = 100;
            ExecutorService executor = Executors.newFixedThreadPool(workers);
            CountDownLatch ready = new CountDownLatch(workers);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(workers);
            for (int worker = 0; worker < workers; worker++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        for (int i = 0; i < perWorker; i++) {
                            allocator.invoke(service, experiment);
                        }
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            executor.shutdownNow();
            assertEquals((long) workers * perWorker, experiment.lastSequence());
        }
    }

    @Test
    void initialArchiveStrideUsesEarliestKnownEndAndSafeCeiling() throws Exception {
        try (ExperimentService service = new ExperimentService(new FileExperimentRepository(tempDir))) {
            Method stride = ExperimentService.class.getDeclaredMethod("initialArchiveStride", SimulationConfig.class);
            stride.setAccessible(true);
            assertEquals(3L, stride.invoke(service, config(100_001L)));
            SimulationConfig bothEnds = new SimulationConfig(
                    "both", config(1L).bodies(), 2.0, PhysicalConstants.GRAVITATIONAL_CONSTANT,
                    1.0e6, 120_000L, 150_000.0);
            assertEquals(2L, stride.invoke(service, bothEnds), "target time (75k steps) ends first");
            SimulationConfig unknown = new SimulationConfig(
                    "unknown", config(1L).bodies(), 2.0, PhysicalConstants.GRAVITATIONAL_CONSTANT,
                    1.0e6, null, null);
            assertEquals(1L, stride.invoke(service, unknown));
            SimulationConfig huge = new SimulationConfig(
                    "huge", config(1L).bodies(), 1.0, PhysicalConstants.GRAVITATIONAL_CONSTANT,
                    1.0e6, Long.MAX_VALUE, null);
            assertTrue((long) stride.invoke(service, huge) > 0L);
        }
    }

    private static SimulationConfig config(long maxSteps) {
        return new SimulationConfig(
                "publication-test",
                List.of(
                        new BodySpec("a", "A", "#ffffff", 1.0e30,
                                Vector3.ZERO, Vector3.ZERO),
                        new BodySpec("b", "B", "#ffffff", 1.0e20,
                                Vector3.of(1.0e11, 0, 0), Vector3.ZERO)),
                60.0,
                PhysicalConstants.GRAVITATIONAL_CONSTANT,
                1.0e6,
                maxSteps,
                null);
    }

    private static boolean await(BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return condition.getAsBoolean();
    }
}
