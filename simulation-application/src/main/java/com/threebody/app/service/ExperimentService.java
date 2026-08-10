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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 实验调度核心服务：管理队列、状态机、工作线程与事件广播。
 *
 * <p>
 * 单工作线程顺序消费队列；同一时刻最多一个实验处于 RUNNING 状态。
 * 状态迁移非法时抛出 {@link IllegalStateTransitionException}（调用方应转为 HTTP 409）。
 * </p>
 */
public class ExperimentService implements AutoCloseable {

    /** 快照发射间隔（步数），目标 ~30 Hz。 */
    static final long SNAPSHOT_INTERVAL = 10;

    /** 轨迹增量发射间隔（步数），目标 ~10 Hz。 */
    static final long TRAJECTORY_INTERVAL = 30;

    /** 指标发射间隔（步数），目标 ~2 Hz。 */
    static final long METRICS_INTERVAL = 150;

    /** 归档采样上限。 */
    static final long ARCHIVE_POINT_LIMIT = 50_000L;

    /** 实时窗口每个天体点数上限。 */
    static final int LIVE_WINDOW_SIZE = 2_000;

    /** 事件列表上限。 */
    static final int MAX_EVENTS = 1_000;

    private final ExperimentRepository repository;
    private final List<ExperimentEventListener> listeners = new CopyOnWriteArrayList<>();

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
    private final Map<String, AtomicLong> eventSequences = new LinkedHashMap<>();

    /** 用于抽样指标的墙钟计时。 */
    private volatile long lastMetricsWallTime = 0L;

    public ExperimentService(ExperimentRepository repository) {
        this.repository = repository;
    }

    // ============================ 生命周期 ============================

    /** 从文件恢复实验列表并确保 RUNNING → PAUSED。 */
    public void initialize() {
        List<Experiment> restored = repository.listAll();
        for (Experiment e : restored) {
            eventSequences.putIfAbsent(e.id(), new AtomicLong(e.lastSequence()));
            if (e.status() == ExperimentStatus.RUNNING) {
                e.setStatus(ExperimentStatus.PAUSED);
                e.addEvent(makeEvent(e, SimulationEventType.STATUS_CHANGE,
                        "应用重启，实验由 RUNNING 恢复为 PAUSED，请手动继续。"));
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

        // 保存所有实验的最终状态
        synchronized (queue) {
            for (Experiment e : experiments.values()) {
                if (e.status() == ExperimentStatus.RUNNING) {
                    e.setStatus(ExperimentStatus.PAUSED);
                    e.addEvent(new SimulationEvent(
                            eventSequences.get(e.id()).incrementAndGet(),
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
    }

    // ============================ 事件监听 ============================

    public void addEventListener(ExperimentEventListener listener) {
        listeners.add(listener);
    }

    public void removeEventListener(ExperimentEventListener listener) {
        listeners.remove(listener);
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
                    e.setEndReason(EndReason.CANCELLED);
                    e.setStatus(ExperimentStatus.CANCELLED);
                    e.setCompletedAt(Instant.now());
                    e.addEvent(makeEvent(e, SimulationEventType.STATUS_CHANGE, "实验已取消。"));
                    repository.save(e);
                    broadcastStatus(e, ExperimentStatus.CANCELLED, prevStatus, "实验已取消。");
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
            queue.remove(id);
            experiments.remove(id);
            eventSequences.remove(id);
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
        TrajectoryInfo traj = e.trajectoryInfo();

        // 初始化
        if (state == null) {
            state = NBodyIntegrator.initialState(config);
            e.setState(state);

            // 计算初始能量基准
            double e0 = MetricsCalculator.totalEnergy(config, state);
            Metrics initMetrics = MetricsCalculator.compute(config, state, e0);
            ExperimentMetrics initEm = toExperimentMetrics(initMetrics, 0.0, null, null, null, null);
            e.setMetrics(initEm);

            // 发射初始快照与指标
            broadcastSnapshot(e, state);
            broadcastMetrics(e, state, initEm);
            repository.save(e);
        }

        lastMetricsWallTime = System.nanoTime();
        long lastSnapshotStep = state.step();
        long lastTrajectoryStep = state.step();
        long lastMetricsStep = state.step();
        long stepCount = 0;

        try {
            while (true) {
                // 检查取消
                if (cancelToken.get()) {
                    // 取消已在 submitAction 中处理
                    workerBusy.set(false);
                    scheduleNext();
                    return;
                }

                // 检查暂停
                if (pauseToken.get()) {
                    e.setStatus(ExperimentStatus.PAUSED);
                    e.addEvent(makeEvent(e, SimulationEventType.STATUS_CHANGE, "实验已暂停。"));
                    broadcastStatus(e, ExperimentStatus.PAUSED, ExperimentStatus.RUNNING, "实验已暂停。");
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
                    repository.save(e);
                    workerBusy.set(false);
                    scheduleNext();
                    return;
                }

                state = result.state();
                stepCount++;
                e.setState(state);

                // 处理近距离事件
                for (NearEncounter ne : result.nearEncounters()) {
                    SimulationEvent ev = new SimulationEvent(
                            eventSequences.get(e.id()).incrementAndGet(),
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

                // 快照 SNAPSHOT（~30 Hz）
                if (stepCount % SNAPSHOT_INTERVAL == 0) {
                    broadcastSnapshot(e, state);
                    lastSnapshotStep = state.step();
                }

                // 轨迹增量 TRAJECTORY（~10 Hz）
                if (stepCount % TRAJECTORY_INTERVAL == 0) {
                    // 轨迹归档在此处整合
                    updateTrajectoryArchive(e, state, traj);
                    broadcastTrajectory(e, state, lastTrajectoryStep, state.step(), traj.sampleStride());
                    lastTrajectoryStep = state.step();
                }

                // 指标 METRICS（~2 Hz）
                if (stepCount % METRICS_INTERVAL == 0) {
                    long now = System.nanoTime();
                    double elapsed = (now - lastMetricsWallTime) / 1_000_000_000.0;
                    lastMetricsWallTime = now;
                    double sps = METRICS_INTERVAL / Math.max(elapsed, 0.001);

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
                    broadcastStatus(e, ExperimentStatus.COMPLETED, ExperimentStatus.RUNNING, reasonMsg);
                    broadcastSnapshot(e, state);
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
            broadcastError(e, "INTERNAL_ERROR", ex.getMessage(), state.step(), false);
            broadcastStatus(e, ExperimentStatus.FAILED, ExperimentStatus.RUNNING,
                    "内部错误：" + ex.getMessage());
            repository.save(e);
            workerBusy.set(false);
            scheduleNext();
        }
    }

    // ============================ 轨迹归档 ============================

    private void updateTrajectoryArchive(Experiment e, SimulationState state, TrajectoryInfo traj) {
        // 追加轨迹点到文件
        repository.appendTrajectoryPoint(e.id(), state, traj.pointLimit());

        // 更新归档元数据
        long newCount = traj.sampleCount() + 1;
        long stride = traj.sampleStride();
        long limit = traj.pointLimit();

        if (newCount > limit) {
            // 达到上限：步长加倍
            stride = Math.min(stride * 2, 100_000L);
            newCount = limit / 2;
        }

        e.setTrajectoryInfo(new TrajectoryInfo(stride, newCount, limit, traj.liveWindowSize()));
    }

    // ============================ 广播辅助方法 ============================

    private void broadcastStatus(Experiment e, ExperimentStatus status, ExperimentStatus prev,
            String message) {
        long seq = eventSequences.get(e.id()).incrementAndGet();
        ExperimentMessage msg = new ExperimentMessage(ExperimentMessageType.STATUS, e.id(),
                seq, Instant.now(),
                new StatusPayload(status.name(), prev != null ? prev.name() : null,
                        e.step(), e.simulationTimeSeconds(),
                        e.endReason() != null ? e.endReason().name() : null,
                        e.progress().completionRatio(), getQueuePosition(e.id()), message));
        fire(msg);
    }

    private void broadcastSnapshot(Experiment e, SimulationState state) {
        long seq = eventSequences.get(e.id()).incrementAndGet();
        List<BodyStatePayload> bodies = state.bodies().stream()
                .map(b -> new BodyStatePayload(b.id(),
                        new Vector3Payload(b.position().x(), b.position().y(), b.position().z()),
                        new Vector3Payload(b.velocity().x(), b.velocity().y(), b.velocity().z())))
                .toList();
        SnapshotPayload payload = new SnapshotPayload(state.step(), state.simulationTimeSeconds(), bodies);
        ExperimentMessage msg = new ExperimentMessage(ExperimentMessageType.SNAPSHOT, e.id(),
                seq, Instant.now(), payload);
        fire(msg);
    }

    private void broadcastTrajectory(Experiment e, SimulationState state, long fromStep, long toStep, long stride) {
        long seq = eventSequences.get(e.id()).incrementAndGet();
        List<TrajectoryPoint> points = List.of(toTrajectoryPoint(state));
        TrajectoryPayload payload = new TrajectoryPayload(fromStep, toStep, stride, points);
        ExperimentMessage msg = new ExperimentMessage(ExperimentMessageType.TRAJECTORY, e.id(),
                seq, Instant.now(), payload);
        fire(msg);
    }

    private void broadcastMetrics(Experiment e, SimulationState state, ExperimentMetrics em) {
        long seq = eventSequences.get(e.id()).incrementAndGet();
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
        ExperimentMessage msg = new ExperimentMessage(ExperimentMessageType.METRICS, e.id(),
                seq, Instant.now(), payload);
        fire(msg);
    }

    private void broadcastNearEncounter(Experiment e, SimulationState state, NearEncounter ne) {
        long seq = eventSequences.get(e.id()).incrementAndGet();
        NearEncounterPayload payload = new NearEncounterPayload(
                state.step(), state.simulationTimeSeconds(),
                List.of(ne.firstBodyId(), ne.secondBodyId()),
                ne.distanceMeters(), ne.thresholdMeters(),
                "天体 " + ne.firstBodyId() + " 与 " + ne.secondBodyId()
                        + " 距离低于 " + String.format("%.2e", ne.thresholdMeters()) + " m。");
        ExperimentMessage msg = new ExperimentMessage(ExperimentMessageType.NEAR_ENCOUNTER, e.id(),
                seq, Instant.now(), payload);
        fire(msg);
    }

    private void broadcastError(Experiment e, String code, String message, long step, boolean recoverable) {
        long seq = eventSequences.get(e.id()).incrementAndGet();
        ErrorPayload payload = new ErrorPayload(code, message, step, recoverable);
        ExperimentMessage msg = new ExperimentMessage(ExperimentMessageType.ERROR, e.id(),
                seq, Instant.now(), payload);
        fire(msg);
    }

    private void fire(ExperimentMessage msg) {
        for (ExperimentEventListener listener : listeners) {
            try {
                listener.onMessage(msg);
            } catch (Exception ignored) {
                // 监听器异常不应影响模拟
            }
        }
    }

    // ============================ 工具方法 ============================

    private SimulationEvent makeEvent(Experiment e, SimulationEventType type, String message) {
        return new SimulationEvent(
                eventSequences.get(e.id()).incrementAndGet(),
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
