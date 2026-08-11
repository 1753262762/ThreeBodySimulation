package com.threebody.app.service;

import com.threebody.app.domain.EndReason;
import com.threebody.app.domain.Experiment;
import com.threebody.app.domain.ExperimentAction;
import com.threebody.app.domain.ExperimentMetrics;
import com.threebody.app.domain.ExperimentStatus;
import com.threebody.app.domain.Progress;
import com.threebody.app.domain.SimulationEvent;
import com.threebody.app.domain.SimulationEventType;
import com.threebody.app.domain.TrajectoryInfo;
import com.threebody.app.event.ExperimentEventListener;
import com.threebody.app.event.ExperimentMessage;
import com.threebody.app.event.ExperimentMessageType;
import com.threebody.app.event.AsyncExperimentEventDispatcher;
import com.threebody.core.BodySpec;
import com.threebody.core.BodyState;
import com.threebody.core.Metrics;
import com.threebody.core.MetricsCalculator;
import com.threebody.core.NBodyIntegrator;
import com.threebody.core.NearEncounter;
import com.threebody.core.NumericalInstabilityException;
import com.threebody.core.SimulationConfig;
import com.threebody.core.SimulationState;
import com.threebody.core.StepResult;
import com.threebody.core.Vector3;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 实验调度核心服务：管理队列、状态机、工作线程与事件广播。
 *
 * <p>
 * 单工作线程顺序消费队列；同一时刻最多一个实验处于 RUNNING 状态。
 * 状态迁移非法时抛出 {@link IllegalStateTransitionException}（调用方应转为 HTTP 409）。
 * </p>
 */
public class ExperimentService implements AutoCloseable {

    /** Wall-clock publication ceilings, independent of simulation SPS. */
    static final double SNAPSHOT_HZ = 60.0;
    static final double TRAJECTORY_HZ = 60.0;
    static final double METRICS_HZ = 2.0;
    static final long SNAPSHOT_PERIOD_NANOS = 1_000_000_000L / 60L;
    static final long TRAJECTORY_PERIOD_NANOS = 1_000_000_000L / 60L;
    static final long METRICS_PERIOD_NANOS = 1_000_000_000L / 2L;

    /**
     * Fast presets used to finish before the browser could display more than
     * one or two states.  Spread sufficiently large finite runs over at least
     * this many snapshot periods while still allowing expensive integrations
     * to run at their natural speed.
    */
    static final long TARGET_VISIBLE_SNAPSHOT_FRAMES = 240L;
    private static final long PACER_POLL_NANOS = 2_000_000L;

    /** Kept for source compatibility with callers that used the old constants. */
    @Deprecated static final long SNAPSHOT_INTERVAL = 10;
    @Deprecated static final long TRAJECTORY_INTERVAL = 30;
    @Deprecated static final long METRICS_INTERVAL = 150;

    /** 归档采样上限。 */
    static final long ARCHIVE_POINT_LIMIT = 50_000L;

    /** 实时窗口每个天体点数上限。 */
    static final int LIVE_WINDOW_SIZE = 8_000;

    /** 事件列表上限。 */
    static final int MAX_EVENTS = 1_000;

    private final ExperimentRepository repository;
    private final MonotonicClock monotonicClock;
    private final boolean realtimePacing;
    private final AsyncExperimentEventDispatcher eventDispatcher;
    private final ArchiveBatchWriter archiveWriter;

    /** 有序队列；仅服务写入，REST 线程只能通过 getExperiments() 读取。 */
    private final List<String> queue = new ArrayList<>();

    /** 按 ID 索引的实验映射。 */
    private final Map<String, Experiment> experiments = new LinkedHashMap<>();

    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "experiment-worker");
        t.setDaemon(true);
        return t;
    });

    /** 当前 RUNNING 实验的取消令牌。 */
    private final AtomicBoolean cancelToken = new AtomicBoolean(false);

    /** 当前 RUNNING 实验的暂停令牌。 */
    private final AtomicBoolean pauseToken = new AtomicBoolean(false);

    /** 等待工作线程执行一次后重新暂停的实验。访问时持有 queue 锁。 */
    private final Set<String> singleStepExperiments = new HashSet<>();

    /** 工作线程是否运行中。 */
    private final AtomicBoolean workerBusy = new AtomicBoolean(false);

    /** 服务关闭后禁止再调度新实验。 */
    private final AtomicBoolean closing = new AtomicBoolean(false);

    /** 事件序号（每实验独立）。 */
    private final Map<String, AtomicLong> eventSequences = new ConcurrentHashMap<>();

    /** Serializes sequence allocation with event enqueue per experiment. */
    private final Map<String, Object> publicationLocks = new ConcurrentHashMap<>();

    /** 用于抽样指标的墙钟计时。 */
    private volatile long lastMetricsWallTime = 0L;

    /** Active near-encounter pairs; events are emitted only on entry edges. */
    private final Map<String, Double> activeNearEncounters = new LinkedHashMap<>();

    public ExperimentService(ExperimentRepository repository) {
        this(repository, (MonotonicClock) System::nanoTime, true);
    }

    public ExperimentService(ExperimentRepository repository, LongSupplier monotonicClock) {
        this(repository, monotonicClock == null
                ? null : (MonotonicClock) monotonicClock::getAsLong);
    }

    public ExperimentService(ExperimentRepository repository, MonotonicClock monotonicClock) {
        this(repository, monotonicClock, false);
    }

    ExperimentService(ExperimentRepository repository, MonotonicClock monotonicClock,
            boolean realtimePacing) {
        this.repository = repository;
        this.monotonicClock = monotonicClock != null ? monotonicClock : System::nanoTime;
        this.realtimePacing = realtimePacing;
        this.eventDispatcher = new AsyncExperimentEventDispatcher();
        this.archiveWriter = new ArchiveBatchWriter(repository, this.monotonicClock);
    }

    // ============================ 生命周期 ============================

    /** 从文件恢复实验列表并确保 RUNNING → PAUSED。 */
    public void initialize() {
        List<Experiment> restored = repository.listAll();
        for (Experiment e : restored) {
            boolean metadataChanged = false;
            eventSequences.putIfAbsent(e.id(), new AtomicLong(e.lastSequence()));
            TrajectoryInfo trajectoryInfo = e.trajectoryInfo();
            if (trajectoryInfo.liveWindowSize() != LIVE_WINDOW_SIZE) {
                e.setTrajectoryInfo(new TrajectoryInfo(
                        trajectoryInfo.sampleStride(), trajectoryInfo.sampleCount(),
                        trajectoryInfo.pointLimit(), LIVE_WINDOW_SIZE));
                metadataChanged = true;
            }
            if (e.status() == ExperimentStatus.RUNNING) {
                e.setStatus(ExperimentStatus.PAUSED);
                e.addEvent(makeEvent(e, SimulationEventType.STATUS_CHANGE,
                        "应用重启，实验由 RUNNING 恢复为 PAUSED，请手动继续。"));
                metadataChanged = true;
            }
            if (metadataChanged) {
                repository.save(e);
            }
            experiments.put(e.id(), e);
            queue.add(e.id());
        }
        scheduleNext();
    }

    @Override
    public void close() {
        // 优雅关闭：先通过取消令牌发出信号，短暂等待，然后回退到强制中断
        closing.set(true);
        cancelToken.set(true);
        pauseToken.set(false); // 解除暂停，允许工作线程检查 cancelToken
        workerExecutor.shutdown();
        try {
            if (!workerExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                workerExecutor.shutdownNow();
                if (!workerExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    System.err.println("[ThreeBodyLab] 工作线程未能及时终止。");
                }
            }
        } catch (InterruptedException e) {
            workerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        try {
            archiveWriter.close();
        } catch (RuntimeException ex) {
            System.err.println("[ThreeBodyLab] archive writer close failed: " + ex.getMessage());
        }

        // 保存所有实验的最终状态
        synchronized (queue) {
            for (Experiment e : experiments.values()) {
                if (e.status() == ExperimentStatus.RUNNING) {
                    e.setStatus(ExperimentStatus.PAUSED);
                    e.addEvent(new SimulationEvent(
                            nextSequence(e),
                            SimulationEventType.STATUS_CHANGE,
                            e.step(), e.simulationTimeSeconds(), Instant.now(),
                            "应用关闭，实验暂停。", null, null));
                }
                try {
                    repository.save(e);
                } catch (Exception ex) {
                    System.err.println("[ThreeBodyLab] 关闭时持久化失败：" + ex.getMessage());
                }
            }
        }
        eventDispatcher.close();
    }

    // ============================ 事件监听 ============================

    public void addEventListener(ExperimentEventListener listener) {
        eventDispatcher.addListener(listener);
    }

    public void removeEventListener(ExperimentEventListener listener) {
        eventDispatcher.removeListener(listener);
    }

    // ============================ 查询 ============================

    public List<Experiment> getExperiments() {
        synchronized (queue) {
            List<Experiment> list = new ArrayList<>();
            for (String id : queue) {
                Experiment e = experiments.get(id);
                if (e != null) list.add(e);
            }
            return List.copyOf(list);
        }
    }

    public List<Experiment> getExperimentsByStatus(Collection<ExperimentStatus> statuses) {
        synchronized (queue) {
            return queue.stream()
                    .map(experiments::get)
                    .filter(e -> e != null && statuses.contains(e.status()))
                    .toList();
        }
    }

    public Experiment getExperiment(String id) {
        synchronized (queue) {
            return experiments.get(id);
        }
    }

    public int getQueuePosition(String id) {
        synchronized (queue) {
            int idx = queue.indexOf(id);
            return idx < 0 ? -1 : idx;
        }
    }

    public ExperimentRepository getExperimentRepository() {
        return repository;
    }

    public long getStorageBytes(String id) {
        return repository.storageBytes(id);
    }

    /** Flushes archive batches before an export/report read. */
    public void flushTrajectory(String id) {
        archiveWriter.flush(id);
    }

    // ============================ 创建与编辑 ============================

    public Experiment createExperiment(String name, SimulationConfig config) {
        SimulationConfig forExperiment = config.withName(name != null && !name.isBlank() ? name : config.name());
        Experiment e = new Experiment(java.util.UUID.randomUUID().toString(),
                forExperiment.name() != null ? forExperiment.name() : "未命名实验", forExperiment);
        eventSequences.put(e.id(), new AtomicLong(0));

        synchronized (queue) {
            experiments.put(e.id(), e);
            queue.add(e.id());
        }
        repository.save(e);
        scheduleNext();
        broadcastStatus(e, ExperimentStatus.QUEUED, null, "实验已创建并入队。");
        return e;
    }

    public Experiment updateExperiment(String id, String name, SimulationConfig config) {
        synchronized (queue) {
            Experiment e = experiments.get(id);
            if (e == null) throw new ExperimentNotFoundException(id);
            if (e.status() != ExperimentStatus.QUEUED) {
                throw new IllegalStateTransitionException(e.status(), ExperimentAction.PAUSE,
                        "只有 QUEUED 状态的实验可以编辑");
            }
            if (name != null) e.setName(name);
            if (config != null) e.setConfig(config);
            repository.save(e);
            return e;
        }
    }

    // ============================ 动作 ============================

    /** 提交控制动作。 */
    public Experiment submitAction(String id, ExperimentAction action, SimulationConfig restartConfig) {
        synchronized (queue) {
            Experiment e = experiments.get(id);
            if (e == null) throw new ExperimentNotFoundException(id);

            switch (action) {
                case PAUSE -> {
                    assertTransition(e, ExperimentAction.PAUSE);
                    if (e.status() == ExperimentStatus.RUNNING) {
                        pauseToken.set(true);
                    } else if (e.status() == ExperimentStatus.QUEUED) {
                        // 队首 QUEUED 实验暂停：标记为 PAUSED，不启动
                        e.setStatus(ExperimentStatus.PAUSED);
                        if (!flushArchive(e)) {
                            scheduleNext();
                            return e;
                        }
                        e.addEvent(makeEvent(e, SimulationEventType.STATUS_CHANGE, "实验已暂停。"));
                        repository.save(e);
                        broadcastStatus(e, ExperimentStatus.PAUSED, ExperimentStatus.QUEUED, "实验已暂停。");
                        scheduleNext();
                    }
                }
                case RESUME -> {
                    assertTransition(e, ExperimentAction.RESUME);
                    e.setStatus(ExperimentStatus.QUEUED);
                    e.addEvent(makeEvent(e, SimulationEventType.STATUS_CHANGE, "实验已恢复，等待执行。"));
                    broadcastStatus(e, ExperimentStatus.QUEUED, ExperimentStatus.PAUSED, "实验已恢复。");
                    // 移到队首
                    queue.remove(e.id());
                    synchronized (queue) {
                        int runningIdx = -1;
                        for (int i = 0; i < queue.size(); i++) {
                            Experiment qe = experiments.get(queue.get(i));
                            if (qe != null && qe.status() == ExperimentStatus.RUNNING) {
                                runningIdx = i;
                                break;
                            }
                        }
                        int insertPos = runningIdx >= 0 ? runningIdx + 1 : 0;
                        queue.add(insertPos, e.id());
                    }
                    repository.save(e);
                    scheduleNext();
                }
                case STEP -> {
                    assertTransition(e, ExperimentAction.STEP);
                    singleStepExperiments.add(e.id());
                    e.addEvent(makeEvent(e, SimulationEventType.STATUS_CHANGE, "单步执行已排队。"));
                    repository.save(e);
                    scheduleNext();
                }
                case RESTART -> {
                    assertTransition(e, ExperimentAction.RESTART);
                    try {
                        archiveWriter.discard(e.id());
                    } catch (RuntimeException failure) {
                        recordArchiveFailure(e, failure);
                        return e;
                    }
                    repository.resetTrajectory(e.id());
                    archiveWriter.reopen(e.id());
                    ExperimentStatus previousStatus = e.status();
                    SimulationConfig newConfig = restartConfig != null ? restartConfig : e.config();
                    e.setConfig(newConfig);
                    e.setState(null);
                    e.setMetrics(null);
                    e.setEndReason(null);
                    e.setCompletedAt(null);
                    e.setStartedAt(null);
                    e.setTrajectoryInfo(new TrajectoryInfo(1L, 0L, ARCHIVE_POINT_LIMIT, LIVE_WINDOW_SIZE));
                    e.clearEvents();
                    e.setPendingRestartConfig(null);
                    e.setStatus(ExperimentStatus.QUEUED);
                    e.addEvent(makeEvent(e, SimulationEventType.STATUS_CHANGE,
                            restartConfig != null ? "已使用新配置重新入队。" : "已使用原配置重新入队。"));
                    broadcastStatus(e, ExperimentStatus.QUEUED,
                            previousStatus, "实验已重新入队。");
                    repository.save(e);
                    scheduleNext();
                }
                case CANCEL -> {
                    assertTransition(e, ExperimentAction.CANCEL);
                    if (e.status() == ExperimentStatus.RUNNING) {
                        cancelToken.set(true);
                    }
                    ExperimentStatus prevStatus = e.status();
                    if (e.status() != ExperimentStatus.RUNNING) {
                        if (!flushArchive(e)) {
                            scheduleNext();
                            return e;
                        }
                    }
                    e.setEndReason(EndReason.CANCELLED);
                    e.setStatus(ExperimentStatus.CANCELLED);
                    e.setCompletedAt(Instant.now());
                    e.addEvent(makeEvent(e, SimulationEventType.STATUS_CHANGE, "实验已取消。"));
                    repository.save(e);
                    broadcastStatus(e, ExperimentStatus.CANCELLED, prevStatus, "实验已取消。");
                    // A queued cancellation has no state and must not invent
                    // one; running/paused experiments publish their current
                    // authoritative state exactly as it exists.
                    publishAuthoritativeState(e, e.state());
                    scheduleNext();
                }
            }
            return e;
        }
    }

    /** 重排队列。 */
    public List<Experiment> reorderQueue(List<String> orderedIds) {
        synchronized (queue) {
            if (orderedIds.size() != queue.size()) {
                throw new QueueConflictException("重排列表必须包含且只包含当前全部实验 ID");
            }
            for (String id : orderedIds) {
                if (!experiments.containsKey(id)) {
                    throw new QueueConflictException("未知实验 ID：" + id);
                }
            }
            // RUNNING 实验的相对位置不可变
            String runningId = null;
            for (String id : queue) {
                Experiment e = experiments.get(id);
                if (e != null && e.status() == ExperimentStatus.RUNNING) {
                    runningId = id;
                    break;
                }
            }
            if (runningId != null) {
                int newIdx = orderedIds.indexOf(runningId);
                if (newIdx < 0) {
                    throw new QueueConflictException("重排列表缺少 RUNNING 实验：" + runningId);
                }
                for (int i = 0; i < newIdx; i++) {
                    Experiment e = experiments.get(orderedIds.get(i));
                    if (e != null && e.status() == ExperimentStatus.RUNNING) {
                        throw new QueueConflictException("RUNNING 实验的相对位置不得改变");
                    }
                }
            }
            queue.clear();
            queue.addAll(orderedIds);
            scheduleNext();
            return getExperiments();
        }
    }

    // ============================ 删除 ============================

    public long deleteExperiment(String id) {
        synchronized (queue) {
            Experiment e = experiments.get(id);
            if (e == null) throw new ExperimentNotFoundException(id);
            if (e.status() == ExperimentStatus.RUNNING) {
                throw new IllegalStateTransitionException(e.status(), ExperimentAction.CANCEL,
                        "RUNNING 实验必须先取消再删除");
            }
            archiveWriter.discard(id);
            queue.remove(id);
            experiments.remove(id);
            eventSequences.remove(id);
            publicationLocks.remove(id);
        }
        return repository.delete(id);
    }

    // ============================ 工作线程 ============================

    private void scheduleNext() {
        if (closing.get() || workerBusy.get()) return;
        Experiment next = null;
        synchronized (queue) {
            for (String id : queue) {
                Experiment e = experiments.get(id);
                if (e != null && (e.status() == ExperimentStatus.QUEUED
                        || singleStepExperiments.contains(e.id()))) {
                    next = e;
                    break;
                }
            }
        }
        if (next != null) {
            workerBusy.set(true);
            Experiment finalNext = next;
            boolean singleStep = singleStepExperiments.remove(finalNext.id());
            workerExecutor.submit(() -> runLoop(finalNext, singleStep));
        }
    }

    private void runLoop(Experiment e, boolean singleStep) {
        if (closing.get()) {
            workerBusy.set(false);
            return;
        }
        cancelToken.set(false);
        pauseToken.set(false);

        if (e.status() == ExperimentStatus.COMPLETED
                || e.status() == ExperimentStatus.CANCELLED
                || e.status() == ExperimentStatus.FAILED) {
            workerBusy.set(false);
            scheduleNext();
            return;
        }

        // 设置 RUNNING 状态
        ExperimentStatus prev = e.status();
        if (prev != ExperimentStatus.RUNNING) {
            e.setStatus(ExperimentStatus.RUNNING);
            if (e.startedAt() == null) {
                e.setStartedAt(Instant.now());
            }
            e.addEvent(makeEvent(e, SimulationEventType.STATUS_CHANGE,
                    prev == ExperimentStatus.PAUSED ? "实验恢复运行。" : "实验开始运行。"));
            broadcastStatus(e, ExperimentStatus.RUNNING, prev,
                    prev == ExperimentStatus.PAUSED ? "实验恢复运行。" : "实验开始运行。");
            repository.save(e);
        }

        SimulationConfig config = e.config();
        SimulationState state = e.state();

        // 初始化
        if (state == null) {
            state = NBodyIntegrator.initialState(config);
            e.setState(state);
            long initialStride = initialArchiveStride(config);
            e.setTrajectoryInfo(new TrajectoryInfo(initialStride, 0L,
                    ARCHIVE_POINT_LIMIT, LIVE_WINDOW_SIZE));
            offerArchivePoint(e, state, true);

            // 计算初始能量基准
            double e0 = MetricsCalculator.totalEnergy(config, state);
            Metrics initMetrics = MetricsCalculator.compute(config, state, e0);
            ExperimentMetrics initEm = toExperimentMetrics(initMetrics, 0.0, null, null, null, null);
            e.setMetrics(initEm);

            // 发射初始快照与指标
            publishAuthoritativeState(e, state);
            broadcastMetrics(e, state, initEm);
            repository.save(e);
        }

        long now = monotonicClock.nanoTime();
        lastMetricsWallTime = now;
        long nextSnapshotDeadline = now + SNAPSHOT_PERIOD_NANOS;
        long nextTrajectoryDeadline = now + TRAJECTORY_PERIOD_NANOS;
        long nextMetricsDeadline = now + METRICS_PERIOD_NANOS;
        long lastTrajectoryStep = state.step();
        long lastMetricsStep = state.step();
        long stepsSinceSnapshot = 0L;
        long snapshotStepBudget = realtimeSnapshotStepBudget(config);
        activeNearEncounters.clear();

        try {
            while (true) {
                // 检查取消
                if (cancelToken.get()) {
                    // submitAction publishes the cancellation snapshot.  A
                    // shutdown/cancel race may reach here before it does, so
                    // only publish when the state has not already been finalised.
                    if (e.status() != ExperimentStatus.CANCELLED) {
                        publishAuthoritativeState(e, e.state());
                    }
                    flushAndReleaseArchive(e);
                    repository.save(e);
                    workerBusy.set(false);
                    scheduleNext();
                    return;
                }

                // 检查暂停
                if (pauseToken.get()) {
                    e.setStatus(ExperimentStatus.PAUSED);
                    e.addEvent(makeEvent(e, SimulationEventType.STATUS_CHANGE, "实验已暂停。"));
                    broadcastStatus(e, ExperimentStatus.PAUSED, ExperimentStatus.RUNNING, "实验已暂停。");
                    publishAuthoritativeState(e, e.state());
                    flushArchive(e);
                    repository.save(e);
                    workerBusy.set(false);
                    scheduleNext();
                    return;
                }

                // 推进
                StepResult result;
                try {
                    result = NBodyIntegrator.step(config, state);
                } catch (NumericalInstabilityException ex) {
                    e.setEndReason(EndReason.ERROR);
                    e.setStatus(ExperimentStatus.FAILED);
                    e.setErrorMessage(ex.getMessage());
                    e.setCompletedAt(Instant.now());
                    e.addEvent(makeEvent(e, SimulationEventType.ERROR,
                            "数值不稳定：" + ex.getMessage()));
                    broadcastError(e, "NUMERICAL_INSTABILITY", ex.getMessage(), state.step(), false);
                    broadcastStatus(e, ExperimentStatus.FAILED, ExperimentStatus.RUNNING,
                            "数值不稳定：" + ex.getMessage());
                    publishAuthoritativeState(e, state);
                    flushAndReleaseArchive(e);
                    repository.save(e);
                    workerBusy.set(false);
                    scheduleNext();
                    return;
                }

                state = result.state();
                e.setState(state);
                stepsSinceSnapshot++;
                offerArchivePoint(e, state, false);

                // 处理近距离事件。核心层每步报告“当前在阈值内”的配对，
                // 应用层只在进入边沿发布；离开使用 1.25x 阈值迟滞。
                Set<String> nearPairsThisStep = new HashSet<>();
                for (NearEncounter ne : result.nearEncounters()) {
                    String key = nearPairKey(ne.firstBodyId(), ne.secondBodyId());
                    nearPairsThisStep.add(key);
                    if (!activeNearEncounters.containsKey(key)) {
                        SimulationEvent ev = new SimulationEvent(
                                nextSequence(e),
                                SimulationEventType.NEAR_ENCOUNTER,
                                state.step(),
                                state.simulationTimeSeconds(),
                                Instant.now(),
                                "天体 " + ne.firstBodyId() + " 与 " + ne.secondBodyId()
                                        + " 距离低于 " + String.format("%.2e", ne.thresholdMeters()) + " m。",
                                List.of(ne.firstBodyId(), ne.secondBodyId()),
                                ne.distanceMeters());
                        e.addEvent(ev);
                        broadcastNearEncounter(e, state, ne);
                    }
                    activeNearEncounters.put(key, ne.thresholdMeters());
                }
                Iterator<Map.Entry<String, Double>> nearIterator =
                        activeNearEncounters.entrySet().iterator();
                while (nearIterator.hasNext()) {
                    Map.Entry<String, Double> entry = nearIterator.next();
                    if (!nearPairsThisStep.contains(entry.getKey())
                            && pairDistance(state, entry.getKey()) > entry.getValue() * 1.25) {
                        nearIterator.remove();
                    }
                }

                if (!singleStep && stepsSinceSnapshot >= snapshotStepBudget) {
                    awaitSnapshotDeadline(nextSnapshotDeadline);
                }
                if (cancelToken.get() || pauseToken.get() || closing.get()) {
                    continue;
                }
                now = monotonicClock.nanoTime();

                // Wall-clock deadlines: at most one publication per type per
                // integration iteration.  A late iteration advances the
                // deadline past 'now' and deliberately skips missed periods.
                if (now >= nextSnapshotDeadline) {
                    broadcastSnapshot(e, state);
                    stepsSinceSnapshot = 0L;
                    nextSnapshotDeadline = advanceDeadline(
                            nextSnapshotDeadline, now, SNAPSHOT_PERIOD_NANOS);
                }

                if (now >= nextTrajectoryDeadline) {
                    broadcastTrajectory(e, state, lastTrajectoryStep, state.step(),
                            Math.max(1L, state.step() - lastTrajectoryStep));
                    lastTrajectoryStep = state.step();
                    nextTrajectoryDeadline = advanceDeadline(
                            nextTrajectoryDeadline, now, TRAJECTORY_PERIOD_NANOS);
                }

                if (now >= nextMetricsDeadline) {
                    double elapsed = (now - lastMetricsWallTime) / 1_000_000_000.0;
                    lastMetricsWallTime = now;
                    double sps = (state.step() - lastMetricsStep) / Math.max(elapsed, 0.001);

                    double e0 = e.metrics() != null ? e.metrics().initialTotalEnergyJoules()
                            : MetricsCalculator.totalEnergy(config, state);
                    Metrics coreMetrics = MetricsCalculator.compute(config, state, e0);

                    // 全时最小距离
                    Double allTimeMinDist = null;
                    Long allTimeMinStep = null;
                    if (e.metrics() != null && e.metrics().allTimeMinimumPairDistanceMeters() != null) {
                        double prevMin = e.metrics().allTimeMinimumPairDistanceMeters();
                        if (coreMetrics.minimumPairDistanceMeters() < prevMin) {
                            allTimeMinDist = coreMetrics.minimumPairDistanceMeters();
                            allTimeMinStep = state.step();
                        } else {
                            allTimeMinDist = prevMin;
                            allTimeMinStep = e.metrics().allTimeMinimumPairDistanceStep();
                        }
                    } else {
                        allTimeMinDist = coreMetrics.minimumPairDistanceMeters();
                        allTimeMinStep = state.step();
                    }

                    ExperimentMetrics em = new ExperimentMetrics(
                            coreMetrics.kineticEnergyJoules(),
                            coreMetrics.potentialEnergyJoules(),
                            coreMetrics.totalEnergyJoules(),
                            coreMetrics.initialTotalEnergyJoules(),
                            coreMetrics.relativeEnergyDrift(),
                            coreMetrics.angularMomentum(),
                            coreMetrics.angularMomentumMagnitude(),
                            coreMetrics.linearMomentum(),
                            coreMetrics.linearMomentumMagnitude(),
                            coreMetrics.minimumPairDistanceMeters(),
                            coreMetrics.minimumPairBodyIds(),
                            allTimeMinDist,
                            allTimeMinStep,
                            sps,
                            elapsed);
                    e.setMetrics(em);
                    broadcastMetrics(e, state, em);
                    lastMetricsStep = state.step();
                    nextMetricsDeadline = advanceDeadline(
                            nextMetricsDeadline, now, METRICS_PERIOD_NANOS);
                }

                // 检查结束条件
                boolean done = false;
                if (config.maxSteps() != null && state.step() >= config.maxSteps()) {
                    e.setEndReason(EndReason.MAX_STEPS);
                    done = true;
                } else if (config.targetSimulationTimeSeconds() != null
                        && state.simulationTimeSeconds() >= config.targetSimulationTimeSeconds()) {
                    e.setEndReason(EndReason.TARGET_TIME);
                    done = true;
                }

                if (done) {
                    e.setStatus(ExperimentStatus.COMPLETED);
                    e.setCompletedAt(Instant.now());

                    // 最终指标
                    double e0 = e.metrics() != null ? e.metrics().initialTotalEnergyJoules()
                            : MetricsCalculator.totalEnergy(config, state);
                    Metrics coreMetrics = MetricsCalculator.compute(config, state, e0);
                    e.setMetrics(toExperimentMetrics(coreMetrics, 0.0,
                            e.metrics() != null ? e.metrics().allTimeMinimumPairDistanceMeters() : null,
                            e.metrics() != null ? e.metrics().allTimeMinimumPairDistanceStep() : null,
                            null, null));

                    String reasonMsg = e.endReason() == EndReason.MAX_STEPS ? "达到最大步数，实验完成。" : "达到目标模拟时间，实验完成。";
                    e.addEvent(makeEvent(e, SimulationEventType.STATUS_CHANGE, reasonMsg));
                    offerArchivePoint(e, state, true);
                    if (!flushAndReleaseArchive(e)) {
                        workerBusy.set(false);
                        scheduleNext();
                        return;
                    }
                    broadcastStatus(e, ExperimentStatus.COMPLETED, ExperimentStatus.RUNNING, reasonMsg);
                    broadcastMetrics(e, state, e.metrics());
                    publishAuthoritativeState(e, state);
                    repository.save(e);
                    workerBusy.set(false);
                    scheduleNext();
                    return;
                }

                if (singleStep && !cancelToken.get()) {
                    e.setStatus(ExperimentStatus.PAUSED);
                    e.addEvent(makeEvent(e, SimulationEventType.STATUS_CHANGE, "单步完成，实验已暂停。"));
                    broadcastStatus(e, ExperimentStatus.PAUSED, ExperimentStatus.RUNNING,
                            "单步完成，实验已暂停。");
                    publishAuthoritativeState(e, state);
                    flushArchive(e);
                    repository.save(e);
                    workerBusy.set(false);
                    scheduleNext();
                    return;
                }
            }
        } catch (Exception ex) {
            e.setEndReason(EndReason.ERROR);
            e.setStatus(ExperimentStatus.FAILED);
            e.setErrorMessage("内部错误：" + ex.getMessage());
            e.setCompletedAt(Instant.now());
            e.addEvent(makeEvent(e, SimulationEventType.ERROR, "内部错误：" + ex.getMessage()));
            long errorStep = state != null ? state.step() : e.step();
            broadcastError(e, "INTERNAL_ERROR", ex.getMessage(), errorStep, false);
            broadcastStatus(e, ExperimentStatus.FAILED, ExperimentStatus.RUNNING,
                    "内部错误：" + ex.getMessage());
            publishAuthoritativeState(e, state != null ? state : e.state());
            flushAndReleaseArchive(e);
            repository.save(e);
            workerBusy.set(false);
            scheduleNext();
        }
    }

    // ============================ 轨迹归档 ============================

    private long initialArchiveStride(SimulationConfig config) {
        long totalSteps = estimatedTotalSteps(config);
        if (totalSteps <= 0L) {
            return 1L;
        }
        return Math.max(1L, 1L + (totalSteps - 1L) / ARCHIVE_POINT_LIMIT);
    }

    private long estimatedTotalSteps(SimulationConfig config) {
        long maxSteps = config.maxSteps() != null ? Math.max(0L, config.maxSteps()) : Long.MAX_VALUE;
        if (config.targetSimulationTimeSeconds() != null && config.timeStepSeconds() > 0.0) {
            double estimate = config.targetSimulationTimeSeconds() / config.timeStepSeconds();
            long targetSteps = estimate >= Long.MAX_VALUE ? Long.MAX_VALUE
                    : Math.max(0L, (long) Math.ceil(estimate));
            return Math.min(maxSteps, targetSteps);
        }
        return maxSteps == Long.MAX_VALUE ? 0L : maxSteps;
    }

    private void offerArchivePoint(Experiment e, SimulationState state, boolean force) {
        if (state == null) {
            return;
        }
        TrajectoryInfo info = e.trajectoryInfo();
        long stride = Math.max(1L, info.sampleStride());
        if (!force && state.step() % stride != 0L) {
            return;
        }
        archiveWriter.offer(e.id(), state, info.pointLimit(), stride, archiveInfo -> {
            TrajectoryInfo current = e.trajectoryInfo();
            e.setTrajectoryInfo(new TrajectoryInfo(archiveInfo.sampleStride(), archiveInfo.pointCount(),
                    current.pointLimit(), current.liveWindowSize()));
        });
    }

    private boolean flushArchive(Experiment e) {
        try {
            archiveWriter.flush(e.id());
            return true;
        } catch (RuntimeException failure) {
            recordArchiveFailure(e, failure);
            return false;
        }
    }

    private boolean flushAndReleaseArchive(Experiment e) {
        try {
            archiveWriter.release(e.id());
            return true;
        } catch (RuntimeException failure) {
            recordArchiveFailure(e, failure);
            return false;
        }
    }

    private void recordArchiveFailure(Experiment e, RuntimeException failure) {
        String message = "trajectory archive failure: " + failure.getMessage();
        e.setEndReason(EndReason.ERROR);
        e.setErrorMessage(message);
        ExperimentStatus previous = e.status();
        if (previous != ExperimentStatus.FAILED) {
            e.setStatus(ExperimentStatus.FAILED);
            e.addEvent(makeEvent(e, SimulationEventType.ERROR, message));
            broadcastError(e, "ARCHIVE_WRITE_FAILED", message, e.step(), false);
            broadcastStatus(e, ExperimentStatus.FAILED, previous, message);
        }
        try {
            repository.save(e);
        } catch (RuntimeException saveFailure) {
            System.err.println("[ThreeBodyLab] unable to persist archive failure: "
                    + saveFailure.getMessage());
        }
    }

    private void publishAuthoritativeState(Experiment e, SimulationState state) {
        if (state != null) {
            broadcastSnapshot(e, state);
            broadcastTrajectory(e, state, state.step(), state.step(), 1L);
        }
    }

    private static String nearPairKey(String first, String second) {
        return first.compareTo(second) <= 0 ? first + "\u0000" + second : second + "\u0000" + first;
    }

    private static double pairDistance(SimulationState state, String key) {
        String[] ids = key.split("\\u0000", -1);
        if (ids.length != 2) {
            return Double.POSITIVE_INFINITY;
        }
        BodyState first = state.bodies().stream()
                .filter(body -> ids[0].equals(body.id())).findFirst().orElse(null);
        BodyState second = state.bodies().stream()
                .filter(body -> ids[1].equals(body.id())).findFirst().orElse(null);
        if (first == null || second == null) {
            return Double.POSITIVE_INFINITY;
        }
        return first.position().subtract(second.position()).length();
    }

    // ============================ 广播辅助方法 ============================

    private void broadcastStatus(Experiment e, ExperimentStatus status, ExperimentStatus prev,
            String message) {
        publish(e, ExperimentMessageType.STATUS,
                new StatusPayload(status.name(), prev != null ? prev.name() : null,
                        e.step(), e.simulationTimeSeconds(),
                        e.endReason() != null ? e.endReason().name() : null,
                        e.progress().completionRatio(), getQueuePosition(e.id()), message));
    }

    private void broadcastSnapshot(Experiment e, SimulationState state) {
        List<BodyStatePayload> bodies = state.bodies().stream()
                .map(b -> new BodyStatePayload(b.id(),
                        new Vector3Payload(b.position().x(), b.position().y(), b.position().z()),
                        new Vector3Payload(b.velocity().x(), b.velocity().y(), b.velocity().z())))
                .toList();
        SnapshotPayload payload = new SnapshotPayload(state.step(), state.simulationTimeSeconds(), bodies);
        publish(e, ExperimentMessageType.SNAPSHOT, payload);
    }

    private void broadcastTrajectory(Experiment e, SimulationState state, long fromStep, long toStep, long stride) {
        List<TrajectoryPoint> points = List.of(toTrajectoryPoint(state));
        TrajectoryPayload payload = new TrajectoryPayload(fromStep, toStep, stride, points);
        publish(e, ExperimentMessageType.TRAJECTORY, payload);
    }

    private void broadcastMetrics(Experiment e, SimulationState state, ExperimentMetrics em) {
        MetricsPayload payload = new MetricsPayload(
                state.step(), state.simulationTimeSeconds(),
                em.kineticEnergyJoules(), em.potentialEnergyJoules(),
                em.totalEnergyJoules(), em.initialTotalEnergyJoules(),
                em.relativeEnergyDrift(),
                new Vector3Payload(em.angularMomentum().x(), em.angularMomentum().y(), em.angularMomentum().z()),
                em.angularMomentumMagnitude(),
                new Vector3Payload(em.linearMomentum().x(), em.linearMomentum().y(), em.linearMomentum().z()),
                em.linearMomentumMagnitude(),
                em.minimumPairDistanceMeters(),
                em.minimumPairBodyIds(),
                em.allTimeMinimumPairDistanceMeters(),
                em.allTimeMinimumPairDistanceStep(),
                em.stepsPerSecond(),
                em.elapsedWallClockSeconds());
        publish(e, ExperimentMessageType.METRICS, payload);
    }

    private void broadcastNearEncounter(Experiment e, SimulationState state, NearEncounter ne) {
        NearEncounterPayload payload = new NearEncounterPayload(
                state.step(), state.simulationTimeSeconds(),
                List.of(ne.firstBodyId(), ne.secondBodyId()),
                ne.distanceMeters(), ne.thresholdMeters(),
                "天体 " + ne.firstBodyId() + " 与 " + ne.secondBodyId()
                        + " 距离低于 " + String.format("%.2e", ne.thresholdMeters()) + " m。");
        publish(e, ExperimentMessageType.NEAR_ENCOUNTER, payload);
    }

    private void broadcastError(Experiment e, String code, String message, long step, boolean recoverable) {
        ErrorPayload payload = new ErrorPayload(code, message, step, recoverable);
        publish(e, ExperimentMessageType.ERROR, payload);
    }

    private void publish(Experiment e, ExperimentMessageType type, Object payload) {
        synchronized (publicationLocks.computeIfAbsent(e.id(), ignored -> new Object())) {
            long seq = nextSequence(e);
            eventDispatcher.publish(new ExperimentMessage(type, e.id(), seq, Instant.now(), payload));
        }
    }

    /** Allocates the one sequence domain shared by persisted events and WS messages. */
    private long nextSequence(Experiment e) {
        Object lock = publicationLocks.computeIfAbsent(e.id(), ignored -> new Object());
        synchronized (lock) {
            AtomicLong sequence = eventSequences.computeIfAbsent(e.id(), ignored ->
                    new AtomicLong(e.lastSequence()));
            long next = sequence.incrementAndGet();
            e.setLastSequence(next);
            return next;
        }
    }

    static long advanceDeadline(long deadline, long now, long period) {
        if (deadline > now) {
            return deadline;
        }
        long missed = (now - deadline) / period + 1L;
        long increment;
        try {
            increment = Math.multiplyExact(missed, period);
            return Math.addExact(deadline, increment);
        } catch (ArithmeticException overflow) {
            return now + period;
        }
    }

    long realtimeSnapshotStepBudget(SimulationConfig config) {
        if (!realtimePacing) {
            return Long.MAX_VALUE;
        }
        long totalSteps = estimatedTotalSteps(config);
        if (totalSteps <= 1L) {
            return Long.MAX_VALUE;
        }
        return Math.max(1L, 1L + (totalSteps - 1L) / TARGET_VISIBLE_SNAPSHOT_FRAMES);
    }

    /**
     * Wait only when computation is ahead of the next display frame.  Polling
     * in short slices keeps pause, cancel and shutdown responsive; late frames
     * are never replayed because {@link #advanceDeadline(long, long, long)}
     * still skips missed periods.
     */
    private void awaitSnapshotDeadline(long deadlineNanos) {
        while (!cancelToken.get() && !pauseToken.get() && !closing.get()) {
            long remaining = deadlineNanos - monotonicClock.nanoTime();
            if (remaining <= 0L) {
                return;
            }
            LockSupport.parkNanos(Math.min(remaining, PACER_POLL_NANOS));
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }

    // ============================ 工具方法 ============================

    private SimulationEvent makeEvent(Experiment e, SimulationEventType type, String message) {
        return new SimulationEvent(
                nextSequence(e),
                type, e.step(), e.simulationTimeSeconds(), Instant.now(), message, null, null);
    }

    private ExperimentMetrics toExperimentMetrics(Metrics m, double elapsed,
            Double allTimeMinDist, Long allTimeMinStep, Double sps, Double elapsedWallClock) {
        return new ExperimentMetrics(
                m.kineticEnergyJoules(), m.potentialEnergyJoules(),
                m.totalEnergyJoules(), m.initialTotalEnergyJoules(),
                m.relativeEnergyDrift(),
                m.angularMomentum(), m.angularMomentumMagnitude(),
                m.linearMomentum(), m.linearMomentumMagnitude(),
                m.minimumPairDistanceMeters(), m.minimumPairBodyIds(),
                allTimeMinDist, allTimeMinStep, sps, elapsedWallClock);
    }

    private TrajectoryPoint toTrajectoryPoint(SimulationState state) {
        List<BodyStatePayload> bodies = state.bodies().stream()
                .map(b -> new BodyStatePayload(b.id(),
                        new Vector3Payload(b.position().x(), b.position().y(), b.position().z()),
                        new Vector3Payload(b.velocity().x(), b.velocity().y(), b.velocity().z())))
                .toList();
        return new TrajectoryPoint(state.step(), state.simulationTimeSeconds(), bodies);
    }

    private void assertTransition(Experiment e, ExperimentAction action) {
        ExperimentStatus current = e.status();
        boolean valid = switch (action) {
            case PAUSE -> current == ExperimentStatus.RUNNING || current == ExperimentStatus.QUEUED;
            case RESUME -> current == ExperimentStatus.PAUSED;
            case STEP -> current == ExperimentStatus.PAUSED;
            case RESTART -> current != ExperimentStatus.RUNNING;
            case CANCEL -> current == ExperimentStatus.QUEUED
                    || current == ExperimentStatus.RUNNING
                    || current == ExperimentStatus.PAUSED;
        };
        if (!valid) {
            throw new IllegalStateTransitionException(current, action,
                    current + " 实验不能执行 " + action + " 动作。");
        }
    }

    // ============================ 载荷类型（与 OpenAPI / WebSocket Schema 一致） ============================

    public record StatusPayload(String status, String previousStatus, long step,
            double simulationTimeSeconds, String endReason, Double completionRatio,
            Integer queuePosition, String message) {}

    public record SnapshotPayload(long step, double simulationTimeSeconds, List<BodyStatePayload> bodies) {}

    public record TrajectoryPayload(long fromStep, long toStep, long stride, List<TrajectoryPoint> points) {}

    public record TrajectoryPoint(long step, double simulationTimeSeconds, List<BodyStatePayload> bodies) {}

    public record MetricsPayload(long step, double simulationTimeSeconds,
            double kineticEnergyJoules, double potentialEnergyJoules,
            double totalEnergyJoules, double initialTotalEnergyJoules,
            double relativeEnergyDrift,
            Vector3Payload angularMomentum, double angularMomentumMagnitude,
            Vector3Payload linearMomentum, double linearMomentumMagnitude,
            double minimumPairDistanceMeters, List<String> minimumPairBodyIds,
            Double allTimeMinimumPairDistanceMeters, Long allTimeMinimumPairDistanceStep,
            Double stepsPerSecond, Double elapsedWallClockSeconds) {}

    public record NearEncounterPayload(long step, double simulationTimeSeconds,
            List<String> bodyIds, double distanceMeters, double thresholdMeters, String message) {}

    public record ErrorPayload(String code, String message, Long step, Boolean recoverable) {}

    public record BodyStatePayload(String id, Vector3Payload position, Vector3Payload velocity) {}

    public record Vector3Payload(double x, double y, double z) {}

    // ============================ 异常 ============================

    public static class ExperimentNotFoundException extends RuntimeException {
        public ExperimentNotFoundException(String id) {
            super("实验不存在：" + id);
        }
    }

    public static class IllegalStateTransitionException extends RuntimeException {
        private final ExperimentStatus current;
        private final ExperimentAction action;

        public IllegalStateTransitionException(ExperimentStatus current, ExperimentAction action, String message) {
            super(message);
            this.current = current;
            this.action = action;
        }

        public ExperimentStatus getCurrent() { return current; }
        public ExperimentAction getAction() { return action; }
    }

    public static class QueueConflictException extends RuntimeException {
        public QueueConflictException(String message) {
            super(message);
        }
    }
}
