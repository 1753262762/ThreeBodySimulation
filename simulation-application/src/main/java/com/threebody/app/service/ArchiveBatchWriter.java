package com.threebody.app.service;

import com.threebody.core.SimulationState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Background archive writer. The simulation thread only appends an immutable
 * state to an in-memory mailbox; file IO, one-time legacy-file loading and
 * deterministic compression all run on the archive worker.
 */
public final class ArchiveBatchWriter implements AutoCloseable {

    public static final int BATCH_SIZE = 512;
    public static final long FLUSH_INTERVAL_NANOS = 1_000_000_000L;

    private final ExperimentRepository repository;
    private final MonotonicClock clock;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "trajectory-archive-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Buffer> buffers = new ConcurrentHashMap<>();
    private final java.util.Set<String> discarded = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ArchiveBatchWriter(ExperimentRepository repository) {
        this(repository, System::nanoTime);
    }

    public ArchiveBatchWriter(ExperimentRepository repository, MonotonicClock clock) {
        this.repository = repository;
        this.clock = clock != null ? clock : System::nanoTime;
    }

    /**
     * Offers one point without waiting for disk. The listener is invoked on
     * the archive worker after the in-memory count/stride changes.
     */
    public void offer(String experimentId, SimulationState state, long pointLimit,
            long sampleStride, Consumer<ArchiveInfo> updateListener) {
        if (closed.get()) {
            throw new IllegalStateException("archive writer is closed");
        }
        if (discarded.contains(experimentId)) {
            throw new IllegalStateException("archive is discarded for " + experimentId);
        }
        if (state == null) {
            return;
        }
        Buffer buffer = buffers.computeIfAbsent(experimentId, Buffer::new);
        boolean schedule;
        synchronized (buffer) {
            if (buffer.failure != null) {
                // Keep the integration path non-blocking; lifecycle flush will
                // surface the stored failure to the caller.
                return;
            }
            buffer.pointLimit = Math.max(1L, pointLimit);
            buffer.sampleStride = Math.max(1L, sampleStride);
            if (updateListener != null) {
                buffer.updateListener = updateListener;
            }
            buffer.incoming.add(state);
            boundIncoming(buffer);
            if (buffer.flushTimer == null) {
                buffer.flushTimer = executor.schedule(
                        () -> scheduleProcessing(buffer, true),
                        FLUSH_INTERVAL_NANOS, TimeUnit.NANOSECONDS);
            }
            schedule = buffer.processing.compareAndSet(false, true);
        }
        if (schedule) {
            executor.execute(() -> process(buffer, false, updateListener));
        }
    }

    /** Forces all points offered so far for one experiment to durable storage. */
    public void flush(String experimentId) {
        Buffer buffer = buffers.get(experimentId);
        if (buffer == null) {
            repository.flushTrajectory(experimentId);
            return;
        }
        waitFor(executor.submit(() -> flushBufferUntilEmpty(buffer, null)));
        repository.flushTrajectory(experimentId);
        rethrowFailure(buffer);
    }

    /** Forces all experiments to durable storage. */
    public void flushAll() {
        List<Future<?>> futures = new ArrayList<>();
        for (Buffer buffer : buffers.values()) {
            futures.add(executor.submit(() -> flushBufferUntilEmpty(buffer, null)));
        }
        for (Future<?> future : futures) {
            waitFor(future);
        }
        repository.flushAllTrajectories();
        for (Buffer buffer : buffers.values()) {
            rethrowFailure(buffer);
        }
    }

    /**
     * Triggers the same one-second deadline used by the scheduler. This is
     * intentionally explicit so deterministic tests do not need to sleep.
     */
    public void flushDue() {
        long now = clock.nanoTime();
        List<Future<?>> futures = new ArrayList<>();
        for (Buffer buffer : buffers.values()) {
            synchronized (buffer) {
                if (!buffer.dirty || now - buffer.lastFlushNanos < FLUSH_INTERVAL_NANOS) {
                    continue;
                }
            }
            futures.add(executor.submit(() -> flushBufferUntilEmpty(buffer, null)));
        }
        for (Future<?> future : futures) {
            waitFor(future);
        }
    }

    /** Flushes and releases all in-memory state for one finished experiment. */
    public void release(String experimentId) {
        Buffer buffer = buffers.get(experimentId);
        if (buffer == null) {
            repository.flushTrajectory(experimentId);
            return;
        }
        RuntimeException failure = null;
        try {
            flush(experimentId);
        } catch (RuntimeException ex) {
            failure = ex;
        } finally {
            if (buffers.remove(experimentId, buffer)) {
                synchronized (buffer) {
                    if (buffer.flushTimer != null) {
                        buffer.flushTimer.cancel(false);
                        buffer.flushTimer = null;
                    }
                    buffer.incoming.clear();
                    buffer.points.clear();
                    buffer.updateListener = null;
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Stops accepting points for an experiment and releases its mailbox. */
    public void discard(String experimentId) {
        discarded.add(experimentId);
        release(experimentId);
    }

    /** Reopens an experiment after its archive file has been reset. */
    public void reopen(String experimentId) {
        discarded.remove(experimentId);
    }

    public Throwable failure(String experimentId) {
        Buffer buffer = buffers.get(experimentId);
        return buffer == null ? null : buffer.failure;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            flushAll();
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            for (Buffer buffer : buffers.values()) {
                synchronized (buffer) {
                    buffer.incoming.clear();
                    buffer.points.clear();
                    buffer.updateListener = null;
                }
            }
            buffers.clear();
            discarded.clear();
        }
    }

    private void scheduleProcessing(Buffer buffer, boolean forceFlush) {
        synchronized (buffer) {
            buffer.flushTimer = null;
            if (buffer.processing.compareAndSet(false, true)) {
                executor.execute(() -> process(buffer, forceFlush, null));
            }
        }
    }

    private void process(Buffer buffer, boolean forceFlush, Consumer<ArchiveInfo> updateListener) {
        try {
            List<SimulationState> incoming;
            synchronized (buffer) {
                incoming = new ArrayList<>(buffer.incoming);
                buffer.incoming.clear();
            }
            if (incoming.isEmpty()) {
                if (forceFlush) {
                    flushBuffer(buffer);
                }
                return;
            }
            ensureLoaded(buffer);
            for (SimulationState state : incoming) {
                appendDistinct(buffer.points, state);
            }
            boolean compressed = compressIfNeeded(buffer);
            buffer.dirty = true;
            buffer.unflushedCount += incoming.size();
            notifyUpdate(buffer);

            long now = clock.nanoTime();
            if (forceFlush || compressed || buffer.unflushedCount >= BATCH_SIZE
                    || now - buffer.lastFlushNanos >= FLUSH_INTERVAL_NANOS) {
                flushBuffer(buffer);
            }
        } catch (Throwable failure) {
            synchronized (buffer) {
                if (buffer.failure == null) {
                    buffer.failure = failure;
                }
            }
        } finally {
            boolean reschedule;
            synchronized (buffer) {
                buffer.processing.set(false);
                reschedule = !buffer.incoming.isEmpty();
                if (reschedule && buffer.processing.compareAndSet(false, true)) {
                    executor.execute(() -> process(buffer, false, updateListener));
                }
            }
        }
    }

    private void flushBufferUntilEmpty(Buffer buffer, Consumer<ArchiveInfo> updateListener) {
        while (true) {
            process(buffer, true, updateListener);
            synchronized (buffer) {
                if (buffer.incoming.isEmpty() && !buffer.processing.get()) {
                    if (buffer.failure != null) {
                        return;
                    }
                    break;
                }
            }
        }
        flushBuffer(buffer);
    }

    private void ensureLoaded(Buffer buffer) {
        if (buffer.loaded) {
            return;
        }
        List<SimulationState> existing = repository.loadTrajectory(buffer.experimentId);
        buffer.points.addAll(existing);
        buffer.persistedCount = existing.size();
        buffer.loaded = true;
        if (compressIfNeeded(buffer)) {
            buffer.dirty = true;
        }
        buffer.lastFlushNanos = clock.nanoTime();
    }

    private void flushBuffer(Buffer buffer) {
        if (buffer.failure != null || !buffer.dirty) {
            return;
        }
        if (buffer.rewriteRequired) {
            repository.replaceTrajectoryPoints(buffer.experimentId,
                    Collections.unmodifiableList(new ArrayList<>(buffer.points)));
            buffer.persistedCount = buffer.points.size();
            buffer.rewriteRequired = false;
        } else if (buffer.persistedCount < buffer.points.size()) {
            int persisted = Math.toIntExact(buffer.persistedCount);
            List<SimulationState> delta = new ArrayList<>(buffer.points.subList(
                    persisted, buffer.points.size()));
            repository.appendTrajectoryPoints(buffer.experimentId, delta, buffer.pointLimit);
            buffer.persistedCount = buffer.points.size();
        }
        repository.flushTrajectory(buffer.experimentId);
        buffer.unflushedCount = 0;
        buffer.lastFlushNanos = clock.nanoTime();
        buffer.dirty = false;
    }

    private void notifyUpdate(Buffer buffer) {
        if (buffer.updateListener != null) {
            buffer.updateListener.accept(new ArchiveInfo(buffer.points.size(), buffer.sampleStride));
        }
    }

    private void rethrowFailure(Buffer buffer) {
        if (buffer.failure != null) {
            throw new ArchiveWriteException("archive write failed for " + buffer.experimentId,
                    buffer.failure);
        }
    }

    private static void appendDistinct(List<SimulationState> points, SimulationState state) {
        if (!points.isEmpty() && points.get(points.size() - 1).step() == state.step()) {
            points.set(points.size() - 1, state);
        } else {
            points.add(state);
        }
    }

    /**
     * Keep a slow-disk mailbox bounded. Sampling is deterministic and keeps
     * both endpoints; the increased stride is reported with the next update.
     */
    private static void boundIncoming(Buffer buffer) {
        long cap = Math.max(1_024L, Math.min(8_192L, buffer.pointLimit * 2L));
        if (buffer.incoming.size() <= cap) {
            return;
        }
        int target = (int) Math.max(2L, cap / 2L);
        List<SimulationState> sampled = uniformlySample(buffer.incoming, target);
        buffer.incoming.clear();
        buffer.incoming.addAll(sampled);
        buffer.sampleStride = Math.min(Long.MAX_VALUE / 2L,
                Math.max(1L, buffer.sampleStride) * 2L);
    }

    private static boolean compressIfNeeded(Buffer buffer) {
        long limit = Math.max(1L, buffer.pointLimit);
        if (buffer.points.size() <= limit) {
            return false;
        }
        long nextStride = Math.min(Long.MAX_VALUE / 2L, Math.max(1L, buffer.sampleStride) * 2L);
        int target = (int) Math.min((long) buffer.points.size(), Math.max(2L, limit / 2L));
        if (limit == 1L) {
            target = 1;
        }
        List<SimulationState> sampled = uniformlySample(buffer.points, target);
        buffer.points.clear();
        buffer.points.addAll(sampled);
        buffer.sampleStride = nextStride;
        buffer.rewriteRequired = true;
        return true;
    }

    private static List<SimulationState> uniformlySample(List<SimulationState> points, int target) {
        if (target >= points.size()) {
            return new ArrayList<>(points);
        }
        if (target <= 1) {
            return List.of(points.get(0));
        }
        List<SimulationState> sampled = new ArrayList<>(target);
        for (int i = 0; i < target; i++) {
            int index = (int) Math.round((double) i * (points.size() - 1) / (target - 1));
            sampled.add(points.get(index));
        }
        return sampled;
    }

    private static void waitFor(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ArchiveWriteException("archive flush interrupted", interrupted);
        } catch (Exception failure) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            if (cause instanceof ArchiveWriteException archiveFailure) {
                throw archiveFailure;
            }
            throw new ArchiveWriteException("archive flush failed", cause);
        }
    }

    public record ArchiveInfo(long pointCount, long sampleStride) {
    }

    public static class ArchiveWriteException extends RuntimeException {
        public ArchiveWriteException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class Buffer {
        private final String experimentId;
        private final AtomicBoolean processing = new AtomicBoolean();
        private final List<SimulationState> incoming = new ArrayList<>();
        private final List<SimulationState> points = new ArrayList<>();
        private long pointLimit = 50_000L;
        private long sampleStride = 1L;
        private long persistedCount;
        private long unflushedCount;
        private long lastFlushNanos;
        private boolean loaded;
        private boolean dirty;
        private boolean rewriteRequired;
        private Throwable failure;
        private ScheduledFuture<?> flushTimer;
        private Consumer<ArchiveInfo> updateListener;

        private Buffer(String experimentId) {
            this.experimentId = experimentId;
        }
    }
}
