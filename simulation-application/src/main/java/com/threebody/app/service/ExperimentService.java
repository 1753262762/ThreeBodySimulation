package com.threebody.app.service;

import com.threebody.app.domain.Diagnostic;
import com.threebody.app.domain.EndReason;
import com.threebody.app.domain.EventPhase;
import com.threebody.app.domain.Experiment;
import com.threebody.app.domain.ExperimentAction;
import com.threebody.app.domain.ExperimentLineage;
import com.threebody.app.domain.ExperimentMetrics;
import com.threebody.app.domain.ExperimentRetryRequest;
import com.threebody.app.domain.ExperimentStatus;
import com.threebody.app.domain.Progress;
import com.threebody.app.domain.SimulationEvent;
import com.threebody.app.domain.SimulationEventType;
import com.threebody.app.domain.SimulationHealthStatus;
import com.threebody.app.domain.TrajectoryInfo;
import com.threebody.app.event.ExperimentEventListener;
import com.threebody.app.event.ExperimentMessage;
import com.threebody.app.event.ExperimentMessageType;
import com.threebody.app.event.AsyncExperimentEventDispatcher;
import com.threebody.core.BodySpec;
import com.threebody.core.BodyState;
import com.threebody.core.ConfigValidator;
import com.threebody.core.Metrics;
import com.threebody.core.MetricsCalculator;
import com.threebody.core.NBodyIntegrator;
import com.threebody.core.NearEncounter;
import com.threebody.core.NumericalInstabilityException;
import com.threebody.core.SimulationConfig;
import com.threebody.core.SimulationState;
import com.threebody.core.StepResult;
import com.threebody.core.ValidationResult;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.UUID;

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

    /** 近遇 UPDATE 最小发布间隔(ns)，500ms。 */
    static final long ENCOUNTER_UPDATE_MIN_NANOS = 500_000_000L;

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

    /** 运行代次（每实验独立）：RESTART/删除时递增，用于使回放任务失效。 */
    private final Map<String, Long> runGenerations = new ConcurrentHashMap<>();

    /** Serializes sequence allocation with event enqueue per experiment. */
    private final Map<String, Object> publicationLocks = new ConcurrentHashMap<>();

    /** Run 级 Health 增量状态；只由单 worker 创建和更新。 */
    private final Map<String, SimulationHealthAnalyzer> healthAnalyzers = new ConcurrentHashMap<>();

    /** 用于抽样指标的墙钟计时。 */
    private volatile long lastMetricsWallTime = 0L;

    /** 活动近遇对：pairKey -> ActiveEncounter；事件仅在进入/更新/退出边沿发布。 */
    private final Map<String, ActiveEncounter> activeEncounters = new LinkedHashMap<>();

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
                    finalizeActiveEncounters(e, e.state());
                    e.setStatus(ExperimentStatus.PAUSED);
                    e.addEvent(SimulationEvent.simple(
                            nextSequence(e), SimulationEventType.STATUS_CHANGE,
                            e.step(), e.simulationTimeSeconds(), Instant.now(),
                            "应用关闭，实验暂停。"));
                } else {
                    finalizeActiveEncounters(e, e.state());
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

    /** 当前运行代次；RESTART/删除后递增，回放任务据此判断是否失效。 */
    public long runGeneration(String id) {
        return runGenerations.getOrDefault(id, 0L);
    }

    private long bumpGeneration(String id) {
        return runGenerations.merge(id, 1L, Long::sum);
    }

    /** Flushes archive batches before an export/report read. */
    public void flushTrajectory(String id) {
        archiveWriter.flush(id);
    }

    /**
     * 查询历史轨迹范围。运行中不强制 flush，归档点可能落后于权威状态。
     *
     * @throws IllegalArgumentException fromStep/toStep/maxPoints 非法或 toStep 超过当前权威步
     */
    public HistorySlice readHistory(String id, long fromStep, Long toStep, int maxPoints) {
        Experiment e = getExperiment(id);
        if (e == null) throw new ExperimentNotFoundException(id);
        if (fromStep < 0L) {
            throw new IllegalArgumentException("fromStep 必须 >= 0");
        }
        long currentStep = e.step();
        long effectiveTo = toStep != null ? toStep : currentStep;
        if (effectiveTo < fromStep) {
            throw new IllegalArgumentException("toStep 必须 >= fromStep");
        }
        if (effectiveTo > currentStep) {
            throw new IllegalArgumentException("toStep 不能超过当前权威步 " + currentStep);
        }
        int limit = maxPoints <= 0 ? 1000 : Math.min(maxPoints, 2000);
        long stride = e.trajectoryInfo().sampleStride();
        return repository.readTrajectoryRange(id, fromStep, effectiveTo, limit, stride);
    }

    // ============================ 创建与编辑 ============================

    public Experiment createExperiment(String name, SimulationConfig config) {
        return createExperiment(name, config, null);
    }

    public Experiment createExperiment(String name, SimulationConfig config,
            ExperimentRetryRequest retryRequest) {
        return createOrReuseExperiment(name, config, retryRequest).experiment();
    }

    /**
     * 创建实验；若已存在同一份规范化配置则复用已有记录。
     *
     * <p>配置名称和校验阶段生成的天体 ID 不参与比较，避免同一组输入因为
     * 展示名称或随机 ID 不同而重复计算。查重与入队在同一个 queue 临界区内
     * 完成，因此并发请求也只会创建一条记录。</p>
     */
    public ExperimentCreationResult createOrReuseExperiment(String name, SimulationConfig config,
            ExperimentRetryRequest retryRequest) {
        ValidationResult vr = ConfigValidator.validate(config);
        if (!vr.valid()) {
            throw new ConfigValidationException(vr.issues());
        }
        SimulationConfig forExperiment = (vr.normalizedConfig() != null ? vr.normalizedConfig() : config)
                .withName(name != null && !name.isBlank() ? name : config.name());
        Experiment e;
        synchronized (queue) {
            ExperimentLineage lineage = buildLineage(retryRequest, forExperiment);
            Experiment duplicate = findPreferredDuplicate(forExperiment);
            if (duplicate != null) {
                return new ExperimentCreationResult(duplicate, true);
            }
            e = new Experiment(java.util.UUID.randomUUID().toString(),
                    forExperiment.name() != null ? forExperiment.name() : "未命名实验",
                    forExperiment, lineage);
            eventSequences.put(e.id(), new AtomicLong(0));
            experiments.put(e.id(), e);
            queue.add(e.id());
        }
        repository.save(e);
        scheduleNext();
        broadcastStatus(e, ExperimentStatus.QUEUED, null, "实验已创建并入队。");
        return new ExperimentCreationResult(e, false);
    }

    private Experiment findPreferredDuplicate(SimulationConfig targetConfig) {
        SimulationConfigKey targetKey = SimulationConfigKey.from(targetConfig);
        Experiment preferred = null;
        for (Experiment candidate : experiments.values()) {
            if (!targetKey.equals(SimulationConfigKey.from(candidate.config()))) continue;
            if (preferred == null || preferDuplicate(candidate, preferred)) {
                preferred = candidate;
            }
        }
        return preferred;
    }

    private static boolean preferDuplicate(Experiment candidate, Experiment current) {
        int candidatePriority = duplicatePriority(candidate.status());
        int currentPriority = duplicatePriority(current.status());
        if (candidatePriority != currentPriority) return candidatePriority < currentPriority;
        return candidate.updatedAt().isAfter(current.updatedAt());
    }

    private static int duplicatePriority(ExperimentStatus status) {
        return switch (status) {
            case RUNNING, QUEUED, PAUSED -> 0;
            case COMPLETED -> 1;
            case CANCELLED, FAILED -> 2;
        };
    }

    private ExperimentLineage buildLineage(ExperimentRetryRequest retryRequest,
            SimulationConfig targetConfig) {
        if (retryRequest == null) return null;
        if (retryRequest.sourceExperimentId() == null || retryRequest.sourceExperimentId().isBlank()
                || retryRequest.recommendationCode() == null || retryRequest.recommendationCode().isBlank()
                || retryRequest.strategy() == null) {
            throw new RetryContextException("对照实验来源、建议编码和保留策略均不能为空");
        }
        Experiment source = experiments.get(retryRequest.sourceExperimentId());
        if (source == null) {
            throw new RetryContextException("源运行记录不存在：" + retryRequest.sourceExperimentId());
        }
        boolean recommendationExists = source.healthReport() != null
                && source.healthReport().recommendations().stream()
                        .anyMatch(item -> retryRequest.recommendationCode().equals(item.code()));
        if (!recommendationExists) {
            throw new RetryContextException("源运行记录没有当前建议：" + retryRequest.recommendationCode());
        }
        SimulationConfig before = source.config();
        List<String> changedFields = changedFields(before, targetConfig);
        ExperimentLineage parent = source.lineage();
        Long beforeSteps = before.estimatedTotalSteps();
        Long afterSteps = targetConfig.estimatedTotalSteps();
        return new ExperimentLineage(
                source.id(), source.name(), parent != null ? parent.rootExperimentId() : source.id(),
                parent != null ? parent.retryDepth() + 1 : 1,
                retryRequest.recommendationCode(), retryRequest.strategy(),
                before.timeStepSeconds(), targetConfig.timeStepSeconds(),
                before.maxSteps(), targetConfig.maxSteps(),
                before.targetSimulationTimeSeconds(), targetConfig.targetSimulationTimeSeconds(),
                estimatedDuration(before, beforeSteps), estimatedDuration(targetConfig, afterSteps),
                beforeSteps, afterSteps, changedFields,
                source.healthReport() != null ? source.healthReport().status() : null);
    }

    private static Double estimatedDuration(SimulationConfig config, Long steps) {
        return steps == null ? null : steps * config.timeStepSeconds();
    }

    private static List<String> changedFields(SimulationConfig before, SimulationConfig after) {
        List<String> changed = new ArrayList<>();
        if (Double.compare(before.timeStepSeconds(), after.timeStepSeconds()) != 0) {
            changed.add("timeStepSeconds");
        }
        if (!Objects.equals(before.maxSteps(), after.maxSteps())) changed.add("maxSteps");
        if (!Objects.equals(before.targetSimulationTimeSeconds(), after.targetSimulationTimeSeconds())) {
            changed.add("targetSimulationTimeSeconds");
        }
        if (Double.compare(before.softeningLengthMeters(), after.softeningLengthMeters()) != 0) {
            changed.add("softeningLengthMeters");
        }
        if (Double.compare(before.gravitationalConstant(), after.gravitationalConstant()) != 0) {
            changed.add("gravitationalConstant");
        }
        if (!Objects.equals(before.bodies(), after.bodies())) changed.add("bodies");
        return changed;
    }

    public Experiment updateExperiment(String id, String name, SimulationConfig config) {
        synchronized (queue) {
            Experiment e = experiments.get(id);
            if (e == null) throw new ExperimentNotFoundException(id);
            if (e.status() != ExperimentStatus.QUEUED) {
                throw new IllegalStateTransitionException(e.status(), ExperimentAction.PAUSE,
                        "只有 QUEUED 状态的实验可以编辑");
            }
            if (config != null) {
                ValidationResult vr = ConfigValidator.validate(config);
                if (!vr.valid()) {
                    throw new ConfigValidationException(vr.issues());
                }
                config = vr.normalizedConfig() != null ? vr.normalizedConfig() : config;
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
                    bumpGeneration(e.id());
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
                    if (restartConfig != null) {
                        ValidationResult vr = ConfigValidator.validate(restartConfig);
                        if (!vr.valid()) {
                            throw new ConfigValidationException(vr.issues());
                        }
                        newConfig = vr.normalizedConfig() != null ? vr.normalizedConfig() : restartConfig;
                    }
                    e.setConfig(newConfig);
                    e.setState(null);
                    e.setMetrics(null);
                    e.setHealthReport(null);
                    healthAnalyzers.remove(e.id());
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
            bumpGeneration(id);
            archiveWriter.discard(id);
            queue.remove(id);
            experiments.remove(id);
            eventSequences.remove(id);
            publicationLocks.remove(id);
            runGenerations.remove(id);
        }
        healthAnalyzers.remove(id);
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

        // 防御性校验：阻止旧文件、Swing 调用者或恢复数据绕过入口的非法配置
        ValidationResult runValidation = ConfigValidator.validate(e.config());
        if (!runValidation.valid()) {
            e.setEndReason(EndReason.ERROR);
            e.setStatus(ExperimentStatus.FAILED);
            e.setErrorMessage("配置校验失败，无法运行");
            e.setCompletedAt(Instant.now());
            e.addEvent(makeEvent(e, SimulationEventType.ERROR, "配置校验失败，无法运行"));
            broadcastError(e, "VALIDATION_FAILED", "配置校验失败，无法运行", e.step(), false);
            broadcastStatus(e, ExperimentStatus.FAILED, ExperimentStatus.RUNNING, "配置校验失败，无法运行。");
            repository.save(e);
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
        boolean healthEligible = state == null || e.healthReport() != null;
        SimulationHealthAnalyzer healthAnalyzer = healthEligible
                ? healthAnalyzers.computeIfAbsent(e.id(), ignored ->
                        new SimulationHealthAnalyzer(config, NBodyIntegrator.initialState(config), e.healthReport()))
                : null;
        activeEncounters.clear();

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
            ExperimentMetrics initEm = toExperimentMetrics(initMetrics, 0.0,
                    initMetrics.minimumPairDistanceMeters(), 0L, null, null);
            e.setMetrics(initEm);
            if (healthAnalyzer != null) {
                e.setHealthReport(healthAnalyzer.analyze(state, initMetrics, false));
            }

            if (hasNumericalHealthFailure(e)) {
                finishNumericalHealthFailure(e, state);
                return;
            }

            // 发射初始快照与指标
            publishAuthoritativeState(e, state);
            broadcastMetrics(e, state, initEm);
            broadcastHealth(e);
            processNearEncounters(e, config, state, NBodyIntegrator.detectNearEncounters(config, state));
            repository.save(e);
        }

        DiagnosticEngine diagnosticEngine = new DiagnosticEngine(config, state);
        boolean hasActiveEncounter = false;

        long now = monotonicClock.nanoTime();
        lastMetricsWallTime = now;
        long nextSnapshotDeadline = now + SNAPSHOT_PERIOD_NANOS;
        long nextTrajectoryDeadline = now + TRAJECTORY_PERIOD_NANOS;
        long nextMetricsDeadline = now + METRICS_PERIOD_NANOS;
        long lastTrajectoryStep = state.step();
        long lastMetricsStep = state.step();
        long stepsSinceSnapshot = 0L;
        long snapshotStepBudget = realtimeSnapshotStepBudget(config);

        try {
            while (true) {
                // 检查取消
                if (cancelToken.get()) {
                    finalizeActiveEncounters(e, e.state());
                    if (refreshHealthAtBoundary(e, healthAnalyzer, e.state(), !activeEncounters.isEmpty())) {
                        finishNumericalHealthFailure(e, e.state());
                        return;
                    }
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
                    finalizeActiveEncounters(e, e.state());
                    if (refreshHealthAtBoundary(e, healthAnalyzer, e.state(), false)) {
                        finishNumericalHealthFailure(e, e.state());
                        return;
                    }
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
                    SimulationState unstableState = state;
                    Diagnostic instability = DiagnosticEngine.numericalInstability(config,
                            unstableState.step(),
                            unstableState.bodies().stream().map(BodyState::id).toList(),
                            unstableState.bodies().stream()
                                    .mapToDouble(b -> unstableState.bodies().stream()
                                            .mapToDouble(o -> o.position().subtract(b.position()).length())
                                            .filter(d -> d > 0.0).min().orElse(Double.NaN))
                                    .filter(Double::isFinite).min().orElse(Double.NaN));
                    SimulationEvent instabilityEvent = diagnosticEvent(e, unstableState, instability);
                    e.upsertEvent(instabilityEvent);
                    publish(e, ExperimentMessageType.DIAGNOSTIC, instabilityEvent);
                    if (healthAnalyzer != null) {
                        e.setHealthReport(healthAnalyzer.fail(ex, unstableState));
                        broadcastHealth(e);
                    }
                    e.setEndReason(EndReason.ERROR);
                    e.setStatus(ExperimentStatus.FAILED);
                    e.setErrorMessage(ex.getMessage());
                    e.setCompletedAt(Instant.now());
                    finalizeActiveEncounters(e, state);
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
                diagnosticEngine.observeStep(state);
                stepsSinceSnapshot++;
                offerArchivePoint(e, state, false);

                hasActiveEncounter = processNearEncounters(e, config, state, result.nearEncounters());

                if (healthAnalyzer != null && healthAnalyzer.shouldSample(state.step())
                        && (e.healthReport() == null || e.healthReport().analyzedStep() != state.step())) {
                    Metrics sampledMetrics = MetricsCalculator.compute(config, state,
                            e.metrics() != null ? e.metrics().initialTotalEnergyJoules()
                                    : MetricsCalculator.totalEnergy(config, NBodyIntegrator.initialState(config)));
                    e.setHealthReport(healthAnalyzer.analyze(state, sampledMetrics, hasActiveEncounter));
                    if (hasNumericalHealthFailure(e)) {
                        finishNumericalHealthFailure(e, state);
                        return;
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
                    if (healthAnalyzer != null
                            && (e.healthReport() == null || e.healthReport().analyzedStep() != state.step())) {
                        e.setHealthReport(healthAnalyzer.analyze(state, coreMetrics, hasActiveEncounter));
                    }
                    if (hasNumericalHealthFailure(e)) {
                        finishNumericalHealthFailure(e, state);
                        return;
                    }
                    broadcastMetrics(e, state, em);
                    broadcastHealth(e);
                    publishDiagnostics(e, diagnosticEngine, state, em, hasActiveEncounter);
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
                    finalizeActiveEncounters(e, state);
                    e.setStatus(ExperimentStatus.COMPLETED);
                    e.setCompletedAt(Instant.now());

                    // 最终指标
                    double e0 = e.metrics() != null ? e.metrics().initialTotalEnergyJoules()
                            : MetricsCalculator.totalEnergy(config, state);
                    Metrics coreMetrics = MetricsCalculator.compute(config, state, e0);
                    Double previousMinimum = e.metrics() != null
                            ? e.metrics().allTimeMinimumPairDistanceMeters() : null;
                    boolean finalIsMinimum = previousMinimum == null
                            || coreMetrics.minimumPairDistanceMeters() < previousMinimum;
                    e.setMetrics(toExperimentMetrics(coreMetrics, 0.0,
                            finalIsMinimum ? coreMetrics.minimumPairDistanceMeters() : previousMinimum,
                            finalIsMinimum ? state.step() : e.metrics().allTimeMinimumPairDistanceStep(),
                            null, null));
                    if (healthAnalyzer != null
                            && (e.healthReport() == null || e.healthReport().analyzedStep() != state.step())) {
                        e.setHealthReport(healthAnalyzer.analyze(state, coreMetrics,
                                !activeEncounters.isEmpty()));
                    }
                    if (hasNumericalHealthFailure(e)) {
                        finishNumericalHealthFailure(e, state);
                        return;
                    }

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
                    broadcastHealth(e);
                    publishAuthoritativeState(e, state);
                    repository.save(e);
                    workerBusy.set(false);
                    scheduleNext();
                    return;
                }

                if (singleStep && !cancelToken.get()) {
                    finalizeActiveEncounters(e, state);
                    if (refreshHealthAtBoundary(e, healthAnalyzer, state, false)) {
                        finishNumericalHealthFailure(e, state);
                        return;
                    }
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
            finalizeActiveEncounters(e, state != null ? state : e.state());
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
        Long estimated = config.estimatedTotalSteps();
        return estimated != null ? estimated : 0L;
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

    private void broadcastHealth(Experiment e) {
        if (e.healthReport() != null) {
            publish(e, ExperimentMessageType.HEALTH, e.healthReport());
        }
    }

    private boolean hasNumericalHealthFailure(Experiment e) {
        return e.healthReport() != null
                && e.healthReport().status() == SimulationHealthStatus.FAILED;
    }

    private void finishNumericalHealthFailure(Experiment e, SimulationState state) {
        finalizeActiveEncounters(e, state);
        String message = e.healthReport() != null && e.healthReport().failure() != null
                ? e.healthReport().failure().message()
                : "A non-finite derived simulation metric was detected.";
        e.setEndReason(EndReason.ERROR);
        e.setStatus(ExperimentStatus.FAILED);
        e.setErrorMessage(message);
        e.setCompletedAt(Instant.now());
        e.addEvent(makeEvent(e, SimulationEventType.ERROR, "Numerical instability: " + message));
        broadcastHealth(e);
        broadcastError(e, "NUMERICAL_INSTABILITY", message, state.step(), false);
        broadcastStatus(e, ExperimentStatus.FAILED, ExperimentStatus.RUNNING,
                "Numerical instability: " + message);
        publishAuthoritativeState(e, state);
        flushAndReleaseArchive(e);
        repository.save(e);
        workerBusy.set(false);
        scheduleNext();
    }

    private void broadcastError(Experiment e, String code, String message, long step, boolean recoverable) {
        ErrorPayload payload = new ErrorPayload(code, message, step, recoverable);
        publish(e, ExperimentMessageType.ERROR, payload);
    }

    private void publish(Experiment e, ExperimentMessageType type, Object payload) {
        publish(e, type, payload, null);
    }

    private void publish(Experiment e, ExperimentMessageType type, Object payload, String mergeKey) {
        synchronized (publicationLocks.computeIfAbsent(e.id(), ignored -> new Object())) {
            long seq = nextSequence(e);
            eventDispatcher.publish(new ExperimentMessage(type, e.id(), seq, Instant.now(), payload, mergeKey));
        }
    }

    /**
     * 发布近遇事件：ENTER/FINAL 走可靠 FIFO；UPDATE 按 eventId 最新值合并，不占可靠队列。
     */
    private void publishEncounter(Experiment e, ActiveEncounter enc, SimulationEvent ev) {
        String mergeKey = ev.phase() == EventPhase.UPDATE
                ? "NEAR_UPDATE:" + enc.eventId
                : null;
        publish(e, ExperimentMessageType.NEAR_ENCOUNTER, ev, mergeKey);
        SimulationHealthAnalyzer analyzer = healthAnalyzers.get(e.id());
        if (analyzer != null) {
            analyzer.observeEncounter(ev);
            e.setHealthReport(analyzer.report());
            broadcastHealth(e);
        }
    }

    /** 处理一步近遇边界，返回当前是否仍有活动近遇。 */
    private boolean processNearEncounters(Experiment e, SimulationConfig config,
            SimulationState state, List<NearEncounter> nearEncounters) {
        Map<String, String> idToName = nameById(config);
        Set<String> nearPairsThisStep = new HashSet<>();
        for (NearEncounter ne : nearEncounters) {
            String key = nearPairKey(ne.firstBodyId(), ne.secondBodyId());
            nearPairsThisStep.add(key);
            ActiveEncounter enc = activeEncounters.get(key);
            double distance = ne.distanceMeters();
            if (enc == null) {
                enc = new ActiveEncounter(
                        UUID.randomUUID().toString(), key,
                        List.of(ne.firstBodyId(), ne.secondBodyId()),
                        nameOf(idToName, ne.firstBodyId()) + " 与 " + nameOf(idToName, ne.secondBodyId()),
                        ne.thresholdMeters(), distance, distance, state.step(),
                        state.simulationTimeSeconds(), midpoint(state, ne.firstBodyId(), ne.secondBodyId()),
                        nextSequence(e));
                activeEncounters.put(key, enc);
                SimulationEvent event = encounterEvent(e, enc, EventPhase.ENTER, state);
                e.upsertEvent(event);
                publishEncounter(e, enc, event);
            } else {
                if (distance < enc.closestDistance) {
                    enc.closestDistance = distance;
                    enc.closestStep = state.step();
                    enc.closestTime = state.simulationTimeSeconds();
                    enc.closestMidpoint = midpoint(state, ne.firstBodyId(), ne.secondBodyId());
                }
                long nowNanos = monotonicClock.nanoTime();
                if (nowNanos - enc.lastUpdatePublishedAt >= ENCOUNTER_UPDATE_MIN_NANOS) {
                    SimulationEvent event = encounterEvent(e, enc, EventPhase.UPDATE, state);
                    e.upsertEvent(event);
                    publishEncounter(e, enc, event);
                    enc.lastUpdatePublishedAt = nowNanos;
                }
            }
        }
        Iterator<Map.Entry<String, ActiveEncounter>> iterator = activeEncounters.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ActiveEncounter> entry = iterator.next();
            ActiveEncounter enc = entry.getValue();
            if (!nearPairsThisStep.contains(entry.getKey())
                    && pairDistance(state, entry.getKey()) > enc.threshold * 1.25) {
                SimulationEvent event = encounterEvent(e, enc, EventPhase.FINAL, state);
                e.upsertEvent(event);
                publishEncounter(e, enc, event);
                iterator.remove();
            }
        }
        return !activeEncounters.isEmpty();
    }

    private boolean refreshHealthAtBoundary(Experiment e, SimulationHealthAnalyzer analyzer,
            SimulationState state, boolean hasActiveEncounter) {
        if (analyzer == null || state == null) return false;
        double initialEnergy = e.metrics() != null ? e.metrics().initialTotalEnergyJoules()
                : MetricsCalculator.totalEnergy(e.config(), NBodyIntegrator.initialState(e.config()));
        Metrics metrics = MetricsCalculator.compute(e.config(), state, initialEnergy);
        Double previousMinimum = e.metrics() != null
                ? e.metrics().allTimeMinimumPairDistanceMeters() : null;
        boolean boundaryIsMinimum = previousMinimum == null
                || metrics.minimumPairDistanceMeters() < previousMinimum;
        e.setMetrics(toExperimentMetrics(metrics, 0.0,
                boundaryIsMinimum ? metrics.minimumPairDistanceMeters() : previousMinimum,
                boundaryIsMinimum ? state.step() : e.metrics().allTimeMinimumPairDistanceStep(),
                null, null));
        if (e.healthReport() == null || e.healthReport().analyzedStep() != state.step()) {
            e.setHealthReport(analyzer.analyze(state, metrics, hasActiveEncounter));
        }
        broadcastMetrics(e, state, e.metrics());
        broadcastHealth(e);
        return hasNumericalHealthFailure(e);
    }

    private SimulationEvent encounterEvent(Experiment e, ActiveEncounter enc, EventPhase phase,
            SimulationState state) {
        return new SimulationEvent(
                enc.sequence,
                enc.eventId,
                SimulationEventType.NEAR_ENCOUNTER,
                phase,
                state.step(),
                state.simulationTimeSeconds(),
                Instant.now(),
                encounterMessage(enc, phase),
                enc.bodyIds,
                enc.closestDistance,
                enc.threshold,
                enc.triggerDistance,
                enc.closestDistance,
                enc.closestStep,
                enc.closestTime,
                enc.closestMidpoint,
                null);
    }

    private String encounterMessage(ActiveEncounter enc, EventPhase phase) {
        return switch (phase) {
            case ENTER -> enc.names + " 进入近距离，当前距离低于阈值。";
            case UPDATE -> enc.names + " 最近距离更新。";
            case FINAL -> enc.names + " 离开近距离，本次最近距离定稿。";
        };
    }

    /** 暂停/完成/取消/失败/关闭时对全部活动近遇定稿并清空活动集合。 */
    private void finalizeActiveEncounters(Experiment e, SimulationState state) {
        if (state == null) {
            activeEncounters.clear();
            return;
        }
        Iterator<Map.Entry<String, ActiveEncounter>> iterator =
                activeEncounters.entrySet().iterator();
        while (iterator.hasNext()) {
            ActiveEncounter enc = iterator.next().getValue();
            SimulationEvent ev = encounterEvent(e, enc, EventPhase.FINAL, state);
            e.upsertEvent(ev);
            publishEncounter(e, enc, ev);
            iterator.remove();
        }
    }

    /** 发布本次指标周期产生的新诊断（FINAL 事件，可靠投递）。 */
    private void publishDiagnostics(Experiment e, DiagnosticEngine engine, SimulationState state,
            ExperimentMetrics em, boolean hasActiveEncounter) {
        Metrics coreMetrics = new Metrics(
                em.kineticEnergyJoules(), em.potentialEnergyJoules(), em.totalEnergyJoules(),
                em.initialTotalEnergyJoules(), em.relativeEnergyDrift(), em.angularMomentum(),
                em.linearMomentum(), em.minimumPairDistanceMeters(), em.minimumPairBodyIds());
        List<Diagnostic> diagnostics = engine.evaluate(state, coreMetrics, hasActiveEncounter);
        for (Diagnostic diagnostic : diagnostics) {
            SimulationEvent ev = diagnosticEvent(e, state, diagnostic);
            e.upsertEvent(ev);
            publish(e, ExperimentMessageType.DIAGNOSTIC, ev);
        }
    }

    private SimulationEvent diagnosticEvent(Experiment e, SimulationState state, Diagnostic diagnostic) {
        return new SimulationEvent(
                nextSequence(e), UUID.randomUUID().toString(),
                SimulationEventType.DIAGNOSTIC, EventPhase.FINAL,
                state.step(), state.simulationTimeSeconds(), Instant.now(),
                diagnostic.summary(), null, null, null, null, null, null, null, null,
                diagnostic);
    }

    private static Map<String, String> nameById(SimulationConfig config) {
        Map<String, String> names = new LinkedHashMap<>();
        for (BodySpec body : config.bodies()) {
            names.put(body.id(), body.name());
        }
        return names;
    }

    private static String nameOf(Map<String, String> idToName, String id) {
        return idToName.getOrDefault(id, id);
    }

    private static Vector3 midpoint(SimulationState state, String firstId, String secondId) {
        BodyState first = state.bodies().stream()
                .filter(b -> firstId.equals(b.id())).findFirst().orElse(null);
        BodyState second = state.bodies().stream()
                .filter(b -> secondId.equals(b.id())).findFirst().orElse(null);
        if (first == null || second == null) {
            return null;
        }
        return first.position().add(second.position()).multiply(0.5);
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
        return SimulationEvent.simple(
                nextSequence(e), type, e.step(), e.simulationTimeSeconds(), Instant.now(), message);
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

    /** 创建接口的内部结果；HTTP 层据此区分 201 与复用时的 200。 */
    public record ExperimentCreationResult(Experiment experiment, boolean reused) {}

    /**
     * 稳定配置键：保留天体顺序和全部可见/物理配置，只忽略顶层名称与自动生成的天体 ID。
     */
    private record SimulationConfigKey(
            List<BodyConfigKey> bodies,
            double timeStepSeconds,
            double gravitationalConstant,
            double softeningLengthMeters,
            Long maxSteps,
            Double targetSimulationTimeSeconds) {

        private static SimulationConfigKey from(SimulationConfig config) {
            List<BodyConfigKey> bodies = config.bodies().stream()
                    .map(BodyConfigKey::from)
                    .toList();
            return new SimulationConfigKey(bodies, config.timeStepSeconds(),
                    config.gravitationalConstant(), config.softeningLengthMeters(),
                    config.maxSteps(), config.targetSimulationTimeSeconds());
        }
    }

    private record BodyConfigKey(
            String name,
            String color,
            double massKg,
            Vector3 position,
            Vector3 velocity) {

        private static BodyConfigKey from(BodySpec body) {
            return new BodyConfigKey(body.name(), body.color(), body.massKg(),
                    body.position(), body.velocity());
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

    public record ErrorPayload(String code, String message, Long step, Boolean recoverable) {}

    public record BodyStatePayload(String id, Vector3Payload position, Vector3Payload velocity) {}

    public record Vector3Payload(double x, double y, double z) {}

    /** 活动近遇状态，按天体对维护。 */
    private static final class ActiveEncounter {
        final String eventId;
        final String key;
        final List<String> bodyIds;
        final String names;
        final double threshold;
        final double triggerDistance;
        final long sequence;
        double closestDistance;
        long closestStep;
        double closestTime;
        Vector3 closestMidpoint;
        long lastUpdatePublishedAt;

        ActiveEncounter(String eventId, String key, List<String> bodyIds, String names,
                double threshold, double triggerDistance, double closestDistance,
                long closestStep, double closestTime, Vector3 closestMidpoint, long sequence) {
            this.eventId = eventId;
            this.key = key;
            this.bodyIds = bodyIds;
            this.names = names;
            this.threshold = threshold;
            this.triggerDistance = triggerDistance;
            this.closestDistance = closestDistance;
            this.closestStep = closestStep;
            this.closestTime = closestTime;
            this.closestMidpoint = closestMidpoint;
            this.sequence = sequence;
            this.lastUpdatePublishedAt = 0L;
        }
    }

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

    public static class RetryContextException extends RuntimeException {
        public RetryContextException(String message) {
            super(message);
        }
    }
}
