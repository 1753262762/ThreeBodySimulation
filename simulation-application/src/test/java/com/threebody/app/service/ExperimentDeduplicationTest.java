package com.threebody.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.threebody.app.domain.Experiment;
import com.threebody.app.domain.ExperimentAction;
import com.threebody.app.domain.ExperimentStatus;
import com.threebody.app.service.persistence.FileExperimentRepository;
import com.threebody.core.BodySpec;
import com.threebody.core.PhysicalConstants;
import com.threebody.core.SimulationConfig;
import com.threebody.core.Vector3;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExperimentDeduplicationTest {

    @TempDir
    Path tempDir;

    @Test
    void sameConfigIgnoresExperimentNameAndGeneratedBodyIds() {
        FileExperimentRepository repository = new FileExperimentRepository(tempDir);
        try (ExperimentService service = new ExperimentService(repository,
                (MonotonicClock) System::nanoTime)) {
            ExperimentService.ExperimentCreationResult first = service.createOrReuseExperiment(
                    "第一次", config(null, null, 500_000L), null);
            ExperimentService.ExperimentCreationResult second = service.createOrReuseExperiment(
                    "第二次", config("body-a", "body-b", 500_000L), null);

            assertFalse(first.reused());
            assertTrue(second.reused());
            assertSame(first.experiment(), second.experiment());
            assertEquals(1, service.getExperiments().size());
            assertEquals("第一次", second.experiment().name());
        }
    }

    @Test
    void changedSimulationParameterCreatesAnotherExperiment() {
        FileExperimentRepository repository = new FileExperimentRepository(tempDir);
        try (ExperimentService service = new ExperimentService(repository,
                (MonotonicClock) System::nanoTime)) {
            ExperimentService.ExperimentCreationResult first = service.createOrReuseExperiment(
                    "原配置", config("a", "b", 500_000L), null);
            ExperimentService.ExperimentCreationResult second = service.createOrReuseExperiment(
                    "修改步数", config("a", "b", 500_001L), null);

            assertFalse(first.reused());
            assertFalse(second.reused());
            assertEquals(2, service.getExperiments().size());
        }
    }

    @Test
    void concurrentEquivalentCreatesProduceOneExperiment() throws Exception {
        FileExperimentRepository repository = new FileExperimentRepository(tempDir);
        try (ExperimentService service = new ExperimentService(repository,
                (MonotonicClock) System::nanoTime)) {
            int requestCount = 8;
            CountDownLatch start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(requestCount);
            try {
                List<Future<ExperimentService.ExperimentCreationResult>> futures = new ArrayList<>();
                for (int i = 0; i < requestCount; i++) {
                    int index = i;
                    futures.add(pool.submit(() -> {
                        start.await();
                        return service.createOrReuseExperiment("并发-" + index,
                                config(null, null, 500_000L), null);
                    }));
                }
                start.countDown();
                List<String> ids = new ArrayList<>();
                for (Future<ExperimentService.ExperimentCreationResult> future : futures) {
                    ids.add(future.get(10, TimeUnit.SECONDS).experiment().id());
                }
                assertEquals(1, ids.stream().distinct().count());
                assertEquals(1, service.getExperiments().size());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void activeThenCompletedThenFailedRecordsHaveReusePriority() {
        FileExperimentRepository repository = new FileExperimentRepository(tempDir);
        SimulationConfig config = config("a", "b", 500_000L);
        Experiment failed = experiment("failed", config, ExperimentStatus.FAILED);
        Experiment completed = experiment("completed", config, ExperimentStatus.COMPLETED);
        Experiment paused = experiment("paused", config, ExperimentStatus.PAUSED);
        repository.save(failed);
        repository.save(completed);
        repository.save(paused);

        try (ExperimentService service = new ExperimentService(repository,
                (MonotonicClock) System::nanoTime)) {
            service.initialize();
            SimulationConfig loadedConfig = service.getExperiment(paused.id()).config();
            assertEquals(paused.id(), service.createOrReuseExperiment("复用", loadedConfig, null).experiment().id());
            service.deleteExperiment(paused.id());
            assertEquals(completed.id(), service.createOrReuseExperiment("复用", loadedConfig, null).experiment().id());
            service.deleteExperiment(completed.id());
            assertEquals(failed.id(), service.createOrReuseExperiment("复用", loadedConfig, null).experiment().id());
        }
    }

    @Test
    void restartKeepsIdAndClearsPreviousTrajectory() throws Exception {
        FileExperimentRepository repository = new FileExperimentRepository(tempDir);
        try (ExperimentService service = new ExperimentService(repository,
                (MonotonicClock) System::nanoTime)) {
            Experiment completed = service.createExperiment("待重启", config("a", "b", 20L));
            assertTrue(waitUntil(10_000L,
                    () -> completed.status() == ExperimentStatus.COMPLETED));
            assertFalse(repository.loadTrajectory(completed.id()).isEmpty());

            Experiment blocker = service.createExperiment("阻塞", config("a", "b", 500_001L));
            assertTrue(waitUntil(5_000L,
                    () -> blocker.status() == ExperimentStatus.RUNNING));

            Experiment restarted = service.submitAction(completed.id(), ExperimentAction.RESTART, null);

            assertEquals(completed.id(), restarted.id());
            assertEquals(ExperimentStatus.QUEUED, restarted.status());
            assertNull(restarted.state());
            assertTrue(repository.loadTrajectory(restarted.id()).isEmpty());
            service.submitAction(blocker.id(), ExperimentAction.CANCEL, null);
        }
    }

    private static Experiment experiment(String id, SimulationConfig config, ExperimentStatus status) {
        Experiment experiment = new Experiment(id, id, config);
        experiment.setStatus(status);
        return experiment;
    }

    private static boolean waitUntil(long timeoutMs, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(20L);
        }
        return false;
    }

    private static SimulationConfig config(String firstId, String secondId, long maxSteps) {
        return new SimulationConfig(
                "配置",
                List.of(
                        new BodySpec(firstId, "太阳", "#ffd166", 1.98892e30,
                                Vector3.ZERO, Vector3.ZERO),
                        new BodySpec(secondId, "地球", "#4d96ff", 5.972e24,
                                Vector3.of(1.496e11, 0, 0), Vector3.of(0, 29783, 0))),
                600.0,
                PhysicalConstants.GRAVITATIONAL_CONSTANT,
                1.0e7,
                maxSteps,
                null);
    }
}
