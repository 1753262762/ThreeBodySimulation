package com.threebody.app.service;

import com.threebody.app.domain.ReplayJob;
import com.threebody.app.domain.ReplayJobStatus;
import com.threebody.app.domain.ReplaySource;
import com.threebody.core.NBodyIntegrator;
import com.threebody.core.SimulationConfig;
import com.threebody.core.SimulationState;
import com.threebody.core.StepResult;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 精确回放服务：独立低优先级单 worker、有界队列（最多 8 个待处理任务）。
 *
 * <p>任务创建时捕获不可变配置、目标步、floor 归档状态与实验运行代次；精确命中直接返回，
 * 缺失时从 floor 或初始状态复用 {@link NBodyIntegrator#step} 重算。只读配置与归档，
 * 不更新实验权威 state/metrics/events/trajectory，也不发实时快照。</p>
 */
public final class ReplayService implements AutoCloseable {

    public static final int MAX_PENDING_JOBS = 8;
    public static final long RESULT_TTL_MILLIS = 10 * 60_000L;
    private static final long PROGRESS_MIN_INTERVAL_NANOS = 100_000_000L;
    private static final int GENERATION_CHECK_STEP_INTERVAL = 128;
    private static final int QUEUE_CAPACITY = MAX_PENDING_JOBS + 8; // 额外容量给运行中任务

    private final ExperimentService experimentService;
    private final ExperimentRepository repository;
    private final ScheduledExecutorService executor;
    private final Map<String, ReplayJob> jobs = new ConcurrentHashMap<>();
    private final ArrayDeque<String> pendingQueue = new ArrayDeque<>();
    private final AtomicLong activeCount = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ReplayService(ExperimentService experimentService, ExperimentRepository repository) {
        this.experimentService = experimentService;
        this.repository = repository;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "replay-worker");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
        this.executor = Executors.newSingleThreadScheduledExecutor(factory);
    }

    /**
     * 创建回放任务。
     *
     * @return 已入队的任务；若精确命中则为 COMPLETED
     * @throws IllegalArgumentException targetStep 非法或超过当前权威步
     * @throws ReplayQueueFullException 待处理任务已达上限
     */
    public ReplayJob create(String experimentId, long targetStep) {
        if (closed.get()) {
            throw new IllegalStateException("replay service is closed");
        }
        ExperimentSnapshot snapshot = snapshot(experimentId);
        if (targetStep < 0L || targetStep > snapshot.currentStep()) {
            throw new IllegalArgumentException("targetStep 必须在 [0, " + snapshot.currentStep() + "] 内");
        }

        String jobId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        if (targetStep == snapshot.currentStep() && snapshot.state() != null) {
            ReplayJob hit = new ReplayJob(jobId, experimentId, targetStep,
                    ReplayJobStatus.COMPLETED, ReplaySource.CURRENT_STATE, null,
                    0L, 0L, 1.0, snapshot.state(), null, now, now, now.plusMillis(RESULT_TTL_MILLIS));
            jobs.put(jobId, hit);
            return hit;
        }

        var exact = repository.findTrajectoryAtStep(experimentId, targetStep);
        if (exact.isPresent()) {
            ReplayJob hit = new ReplayJob(jobId, experimentId, targetStep,
                    ReplayJobStatus.COMPLETED, ReplaySource.ARCHIVE_EXACT, null,
                    0L, 0L, 1.0, exact.get(), null, now, now, now.plusMillis(RESULT_TTL_MILLIS));
            jobs.put(jobId, hit);
            return hit;
        }

        synchronized (pendingQueue) {
            if (pendingQueue.size() >= MAX_PENDING_JOBS) {
                throw new ReplayQueueFullException("回放待处理任务已达 " + MAX_PENDING_JOBS + " 个上限");
            }
            pendingQueue.addLast(jobId);
        }

        ReplayJob queued = new ReplayJob(jobId, experimentId, targetStep,
                ReplayJobStatus.QUEUED, null, null, 0L, 0L, 0.0, null, null,
                now, now, null);
        jobs.put(jobId, queued);
        scheduleNext();
        return queued;
    }

    public ReplayJob get(String jobId) {
        ReplayJob job = jobs.get(jobId);
        if (job == null || expired(job)) {
            throw new ReplayJobNotFoundException("回放任务不存在或已过期");
        }
        return job;
    }

    /** 删除任务：运行中进入 CANCELLED，已终态任务保持原终态。 */
    public void delete(String jobId) {
        ReplayJob job = jobs.get(jobId);
        if (job == null || expired(job)) {
            throw new ReplayJobNotFoundException("回放任务不存在或已过期");
        }
        if (job.status() == ReplayJobStatus.QUEUED) {
            synchronized (pendingQueue) {
                pendingQueue.remove(jobId);
            }
            jobs.put(jobId, finish(job, ReplayJobStatus.CANCELLED, null, null));
        } else if (job.status() == ReplayJobStatus.RUNNING) {
            jobs.put(jobId, finish(job, ReplayJobStatus.CANCELLED, null, null));
        }
        // COMPLETED/CANCELLED/FAILED 保持原终态，仅等待 TTL 清理
    }

    /** 运行代次失效或任务被取消时由工作线程调用。 */
    private void cancelAndRemove(String jobId) {
        ReplayJob job = jobs.get(jobId);
        if (job != null && (job.status() == ReplayJobStatus.QUEUED
                || job.status() == ReplayJobStatus.RUNNING)) {
            jobs.put(jobId, finish(job, ReplayJobStatus.CANCELLED, null, null));
        }
        activeCount.decrementAndGet();
        scheduleNext();
    }

    private void scheduleNext() {
        if (closed.get()) {
            return;
        }
        synchronized (pendingQueue) {
            if (activeCount.get() > 0 || pendingQueue.isEmpty()) {
                return;
            }
            String jobId = pendingQueue.pollFirst();
            if (jobId == null) {
                return;
            }
            activeCount.incrementAndGet();
            executor.execute(() -> run(jobId));
        }
    }

    private void run(String jobId) {
        ReplayJob job = jobs.get(jobId);
        if (job == null) {
            activeCount.decrementAndGet();
            scheduleNext();
            return;
        }
        try {
            jobs.put(jobId, withStatus(job, ReplayJobStatus.RUNNING));
            ReplayJob completed = recompute(jobId, job);
            jobs.put(jobId, completed);
        } catch (Throwable failure) {
            ReplayJob jobSnapshot = jobs.get(jobId);
            if (jobSnapshot != null) {
                jobs.put(jobId, finish(jobSnapshot, ReplayJobStatus.FAILED,
                        failure.getMessage(), null));
            }
        } finally {
            activeCount.decrementAndGet();
            scheduleNext();
        }
    }

    private ReplayJob recompute(String jobId, ReplayJob job) {
        ExperimentSnapshot snapshot = snapshot(job.experimentId());
        SimulationConfig config = snapshot.config();
        long generation = snapshot.generation();

        // floor 起点
        var floor = repository.findTrajectoryAtOrBefore(job.experimentId(), job.targetStep());
        SimulationState start;
        long startStep;
        ReplaySource source;
        if (floor.isPresent() && floor.get().step() >= 0L) {
            start = floor.get();
            startStep = start.step();
            source = ReplaySource.RECOMPUTED;
        } else {
            start = NBodyIntegrator.initialState(config);
            startStep = 0L;
            source = ReplaySource.RECOMPUTED;
        }
        long totalSteps = Math.max(1L, job.targetStep() - startStep);

        long completed = 0L;
        long lastProgressNanos = System.nanoTime();
        SimulationState current = start;
        while (current.step() < job.targetStep()) {
            if (closed.get() || experimentService.runGeneration(job.experimentId()) != generation) {
                return finish(job, ReplayJobStatus.CANCELLED, null, null);
            }
            StepResult result = NBodyIntegrator.step(config, current);
            current = result.state();
            completed++;
            long now = System.nanoTime();
            if (completed % GENERATION_CHECK_STEP_INTERVAL == 0L
                    || now - lastProgressNanos >= PROGRESS_MIN_INTERVAL_NANOS) {
                jobs.put(jobId, withProgress(job, ReplayJobStatus.RUNNING,
                        completed, totalSteps, source, startStep));
                lastProgressNanos = now;
            }
        }
        Instant now = Instant.now();
        return new ReplayJob(job.jobId(), job.experimentId(), job.targetStep(),
                ReplayJobStatus.COMPLETED, source, startStep,
                completed, totalSteps, 1.0, current, null,
                job.createdAt(), now, now.plusMillis(RESULT_TTL_MILLIS));
    }

    private ReplayJob withProgress(ReplayJob job, ReplayJobStatus status,
            long completed, long totalSteps, ReplaySource source, Long baseStep) {
        double progress = Math.min(1.0, (double) completed / Math.max(1L, totalSteps));
        return new ReplayJob(job.jobId(), job.experimentId(), job.targetStep(), status,
                source, baseStep, completed, totalSteps, progress, null, null,
                job.createdAt(), Instant.now(), null);
    }

    private ReplayJob finish(ReplayJob job, ReplayJobStatus status, String error, SimulationState result) {
        Instant now = Instant.now();
        double progress = status == ReplayJobStatus.COMPLETED ? 1.0 : job.progress();
        return new ReplayJob(job.jobId(), job.experimentId(), job.targetStep(), status,
                job.source(), job.baseStep(), job.completedSteps(), job.totalSteps(), progress,
                result != null ? result : job.result(), error != null ? error : job.error(),
                job.createdAt(), now, now.plusMillis(RESULT_TTL_MILLIS));
    }

    private ReplayJob withStatus(ReplayJob job, ReplayJobStatus status) {
        return new ReplayJob(job.jobId(), job.experimentId(), job.targetStep(), status,
                job.source(), job.baseStep(), job.completedSteps(), job.totalSteps(), job.progress(),
                job.result(), job.error(), job.createdAt(), Instant.now(), job.expiresAt());
    }

    private boolean expired(ReplayJob job) {
        return job.expiresAt() != null && Instant.now().isAfter(job.expiresAt());
    }

    private ExperimentSnapshot snapshot(String experimentId) {
        com.threebody.app.domain.Experiment experiment = experimentService.getExperiment(experimentId);
        if (experiment == null) {
            throw new com.threebody.app.service.ExperimentService.ExperimentNotFoundException(experimentId);
        }
        return new ExperimentSnapshot(experiment.config(), experiment.state(), experiment.step(),
                experimentService.runGeneration(experimentId));
    }

    private static final class ExperimentSnapshot {
        private final SimulationConfig config;
        private final SimulationState state;
        private final long currentStep;
        private final long generation;

        private ExperimentSnapshot(SimulationConfig config, SimulationState state,
                long currentStep, long generation) {
            this.config = config;
            this.state = state;
            this.currentStep = currentStep;
            this.generation = generation;
        }

        SimulationConfig config() { return config; }
        SimulationState state() { return state; }
        long currentStep() { return currentStep; }
        long generation() { return generation; }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (pendingQueue) {
            pendingQueue.clear();
        }
        for (Map.Entry<String, ReplayJob> entry : jobs.entrySet()) {
            ReplayJob job = entry.getValue();
            if (job.status() == ReplayJobStatus.QUEUED
                    || job.status() == ReplayJobStatus.RUNNING) {
                jobs.put(entry.getKey(), finish(job, ReplayJobStatus.CANCELLED, null, null));
            }
        }
        executor.shutdownNow();
    }

    // ============================ 异常 ============================

    public static class ReplayQueueFullException extends RuntimeException {
        public ReplayQueueFullException(String message) {
            super(message);
        }
    }

    public static class ReplayJobNotFoundException extends RuntimeException {
        public ReplayJobNotFoundException(String message) {
            super(message);
        }
    }
}
