package com.threebody.app.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.threebody.core.SimulationConfig;
import com.threebody.core.SimulationState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 实验聚合根。字段由应用层在工作线程中更新，REST 线程读取时通过同步方法获得一致快照。
 * 状态迁移合法性由 ExperimentService 校验。
 */
public final class Experiment {

    private final String id;
    private String name;
    private volatile ExperimentStatus status;
    private final Instant createdAt;
    private volatile Instant updatedAt;
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile EndReason endReason;
    private volatile SimulationConfig config;
    private volatile SimulationState state;
    private volatile ExperimentMetrics metrics;
    private final List<SimulationEvent> events = new ArrayList<>();
    private volatile TrajectoryInfo trajectoryInfo;
    private volatile long lastSequence;
    private volatile String errorMessage;

    /** 待处理的重启配置；由动作提交线程写入，工作线程消费后清空。 */
    @JsonIgnore
    private volatile SimulationConfig pendingRestartConfig;

    public Experiment(String id, String name, SimulationConfig config) {
        this.id = id;
        this.name = name;
        this.config = config;
        this.status = ExperimentStatus.QUEUED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.trajectoryInfo = new TrajectoryInfo(1L, 0L, 50_000L, 8_000);
    }

    @JsonCreator
    public Experiment(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("status") ExperimentStatus status,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("updatedAt") Instant updatedAt,
            @JsonProperty("startedAt") Instant startedAt,
            @JsonProperty("completedAt") Instant completedAt,
            @JsonProperty("endReason") EndReason endReason,
            @JsonProperty("config") SimulationConfig config,
            @JsonProperty("state") SimulationState state,
            @JsonProperty("metrics") ExperimentMetrics metrics,
            @JsonProperty("events") List<SimulationEvent> events,
            @JsonProperty("trajectoryInfo") TrajectoryInfo trajectoryInfo,
            @JsonProperty("lastSequence") long lastSequence,
            @JsonProperty("errorMessage") String errorMessage) {
        this.id = id;
        this.name = name;
        this.status = status != null ? status : ExperimentStatus.QUEUED;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.endReason = endReason;
        this.config = config;
        this.state = state;
        this.metrics = metrics;
        if (events != null) {
            this.events.addAll(events);
        }
        this.trajectoryInfo = trajectoryInfo != null
                ? trajectoryInfo
                : new TrajectoryInfo(1L, 0L, 50_000L, 8_000);
        this.lastSequence = lastSequence;
        this.errorMessage = errorMessage;
    }

    public synchronized String id() { return id; }
    public synchronized String name() { return name; }
    public synchronized void setName(String name) { this.name = name; touch(); }
    public synchronized ExperimentStatus status() { return status; }
    public synchronized void setStatus(ExperimentStatus status) { this.status = status; touch(); }
    public synchronized Instant createdAt() { return createdAt; }
    public synchronized Instant updatedAt() { return updatedAt; }
    public synchronized Instant startedAt() { return startedAt; }
    public synchronized void setStartedAt(Instant startedAt) { this.startedAt = startedAt; touch(); }
    public synchronized Instant completedAt() { return completedAt; }
    public synchronized void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; touch(); }
    public synchronized EndReason endReason() { return endReason; }
    public synchronized void setEndReason(EndReason endReason) { this.endReason = endReason; touch(); }
    public synchronized SimulationConfig config() { return config; }
    public synchronized void setConfig(SimulationConfig config) { this.config = config; touch(); }
    public synchronized SimulationState state() { return state; }
    public synchronized void setState(SimulationState state) { this.state = state; touch(); }
    public synchronized ExperimentMetrics metrics() { return metrics; }
    public synchronized void setMetrics(ExperimentMetrics metrics) { this.metrics = metrics; touch(); }
    public synchronized TrajectoryInfo trajectoryInfo() { return trajectoryInfo; }
    public synchronized void setTrajectoryInfo(TrajectoryInfo info) { this.trajectoryInfo = info; }
    public synchronized long lastSequence() { return lastSequence; }
    public synchronized void setLastSequence(long lastSequence) { this.lastSequence = lastSequence; }
    public synchronized String errorMessage() { return errorMessage; }
    public synchronized void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; touch(); }
    public synchronized SimulationConfig pendingRestartConfig() { return pendingRestartConfig; }
    public synchronized void setPendingRestartConfig(SimulationConfig config) { this.pendingRestartConfig = config; }

    public synchronized List<SimulationEvent> events() { return List.copyOf(events); }

    public synchronized void addEvent(SimulationEvent event) {
        events.add(event);
        if (events.size() > 1000) {
            events.remove(0);
        }
        touch();
    }

    /**
     * 按逻辑事件 upsert：首次 ENTER/一次性诊断追加；UPDATE/FINAL 原位替换同 eventId 记录。
     * 1,000 条上限按"逻辑事件"计数，不按更新次数计数；达到上限时删除最旧的已定稿事件，
     * 不删除仍活动的近遇（phase 为 ENTER/UPDATE）。
     */
    public synchronized void upsertEvent(SimulationEvent event) {
        if (event.eventId() != null) {
            for (int i = 0; i < events.size(); i++) {
                if (event.eventId().equals(events.get(i).eventId())) {
                    events.set(i, event);
                    touch();
                    return;
                }
            }
        }
        events.add(event);
        while (events.size() > 1000) {
            int evict = oldestFinalizedIndex(events);
            events.remove(evict < 0 ? 0 : evict);
        }
        touch();
    }

    private static int oldestFinalizedIndex(List<SimulationEvent> list) {
        for (int i = 0; i < list.size(); i++) {
            SimulationEvent ev = list.get(i);
            if (ev.phase() == null || ev.phase() == EventPhase.FINAL) {
                return i;
            }
        }
        return -1;
    }

    /** 清空事件列表，用于 RESTART。 */
    public synchronized void clearEvents() {
        events.clear();
        touch();
    }

    public synchronized double simulationTimeSeconds() {
        return state == null ? 0.0 : state.simulationTimeSeconds();
    }

    public synchronized long step() {
        return state == null ? 0L : state.step();
    }

    public synchronized Progress progress() {
        return Progress.of(config, step(), simulationTimeSeconds(), metrics == null ? null : metrics.stepsPerSecond());
    }

    private void touch() {
        try {
            updatedAt = Instant.now();
        } catch (RuntimeException ignored) {
            // 时间源异常不影响状态更新。
        }
    }
}
