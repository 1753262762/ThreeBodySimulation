package com.threebody.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.threebody.app.domain.Experiment;
import com.threebody.core.BodyState;
import com.threebody.core.SimulationState;
import com.threebody.core.Vector3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class ArchiveBatchWriterTest {

    @Test
    void offersAreNonBlockingAndFlushesAtBatchSize() {
        RecordingRepository repository = new RecordingRepository();
        repository.writeDelayMillis = 400L;
        try (ArchiveBatchWriter writer = new ArchiveBatchWriter(repository)) {
            long start = System.nanoTime();
            for (int i = 0; i < ArchiveBatchWriter.BATCH_SIZE; i++) {
                writer.offer("batch", state(i), 50_000L, 1L, null);
            }
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
            assertTrue(elapsedMillis < 250L, "offer must not wait for storage: " + elapsedMillis + " ms");
            writer.flush("batch");
            assertEquals(ArchiveBatchWriter.BATCH_SIZE, repository.loadTrajectory("batch").size());
            assertTrue(repository.batchCalls > 0);
        }
    }

    @Test
    void oneSecondDeadlineFlushesWithoutAnotherOffer() throws Exception {
        RecordingRepository repository = new RecordingRepository();
        AtomicLong now = new AtomicLong(0L);
        try (ArchiveBatchWriter writer = new ArchiveBatchWriter(repository, now::get)) {
            writer.offer("timer", state(0), 50_000L, 1L, null);
            writer.flush("timer");
            assertEquals(1, repository.loadTrajectory("timer").size());
            CountDownLatch processed = new CountDownLatch(1);
            writer.offer("timer2", state(1), 50_000L, 1L, ignored -> processed.countDown());
            assertTrue(processed.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(repository.loadTrajectory("timer2").isEmpty());
            now.set(ArchiveBatchWriter.FLUSH_INTERVAL_NANOS);
            writer.flushDue();
            assertEquals(1, repository.loadTrajectory("timer2").size());
        }
    }

    @Test
    void compressionPreservesEndpointsAndReportsStride() {
        RecordingRepository repository = new RecordingRepository();
        List<ArchiveBatchWriter.ArchiveInfo> updates = new CopyOnWriteArrayList<>();
        try (ArchiveBatchWriter writer = new ArchiveBatchWriter(repository)) {
            for (int i = 0; i < 25; i++) {
                writer.offer("compress", state(i), 10L, 2L, updates::add);
            }
            writer.flush("compress");
        }
        List<SimulationState> points = repository.loadTrajectory("compress");
        assertTrue(points.size() <= 10);
        assertEquals(0L, points.get(0).step());
        assertEquals(24L, points.get(points.size() - 1).step());
        assertTrue(updates.stream().anyMatch(info -> info.sampleStride() >= 4L));
    }

    @Test
    void releaseClearsMailboxAndFailureIsObservable() {
        RecordingRepository repository = new RecordingRepository();
        try (ArchiveBatchWriter writer = new ArchiveBatchWriter(repository)) {
            writer.offer("released", state(0), 50_000L, 1L, null);
            writer.release("released");
            assertEquals(1, repository.loadTrajectory("released").size());
        }

        RecordingRepository failing = new RecordingRepository();
        failing.failWrites = true;
        ArchiveBatchWriter writer = new ArchiveBatchWriter(failing);
        writer.offer("failure", state(0), 50_000L, 1L, null);
        assertThrows(ArchiveBatchWriter.ArchiveWriteException.class, () -> writer.flush("failure"));
        assertThrows(ArchiveBatchWriter.ArchiveWriteException.class, writer::close);
    }

    private static SimulationState state(long step) {
        return new SimulationState(step, step, List.of(
                new BodyState("body", Vector3.of(step, 0, 0), Vector3.ZERO)));
    }

    private static final class RecordingRepository implements ExperimentRepository {
        private final Map<String, List<SimulationState>> points = new ConcurrentHashMap<>();
        private volatile int batchCalls;
        private boolean failWrites;
        private volatile long writeDelayMillis;

        @Override public List<Experiment> listAll() { return List.of(); }
        @Override public void save(Experiment experiment) { }
        @Override public long delete(String id) { points.remove(id); return 0L; }
        @Override public long storageBytes(String id) { return points.getOrDefault(id, List.of()).size(); }

        @Override
        public synchronized void appendTrajectoryPoints(String id, List<SimulationState> states, long limit) {
            if (failWrites) {
                throw new IllegalStateException("injected archive failure");
            }
            if (writeDelayMillis > 0L) {
                try {
                    Thread.sleep(writeDelayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            batchCalls++;
            points.computeIfAbsent(id, ignored -> new CopyOnWriteArrayList<>()).addAll(states);
        }

        @Override public synchronized void appendTrajectoryPoint(String id, SimulationState state, long limit) {
            appendTrajectoryPoints(id, List.of(state), limit);
        }
        @Override public synchronized List<SimulationState> loadTrajectory(String id) {
            return new ArrayList<>(points.getOrDefault(id, List.of()));
        }
        @Override public synchronized void replaceTrajectoryPoints(String id, List<SimulationState> states) {
            points.put(id, new CopyOnWriteArrayList<>(states));
        }
        @Override public void flushTrajectory(String id) { }
        @Override public void flushAllTrajectories() { }
        @Override public void resetTrajectory(String id) { points.remove(id); }
    }
}
