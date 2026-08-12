package com.threebody.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.threebody.app.domain.EventPhase;
import com.threebody.app.domain.Experiment;
import com.threebody.app.domain.ExperimentAction;
import com.threebody.app.domain.ExperimentStatus;
import com.threebody.app.domain.SimulationEvent;
import com.threebody.app.domain.SimulationEventType;
import com.threebody.app.event.ExperimentMessage;
import com.threebody.app.service.persistence.FileExperimentRepository;
import com.threebody.core.BodySpec;
import com.threebody.core.PhysicalConstants;
import com.threebody.core.SimulationConfig;
import com.threebody.core.Vector3;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * B3 近遇生命周期测试：ENTER/UPDATE/FINAL、1.25x 退出迟滞、暂停定稿与 eventId 稳定。
 * 事件按 eventId upsert，REST 列表只保留最新修订，因此生命周期断言使用 WS 消息。
 */
class CloseEncounterLifecycleTest {

    @TempDir
    Path tempDir;

    private FileExperimentRepository repo;
    private ExperimentService service;
    private List<ExperimentMessage> captured;

    @BeforeEach
    void setUp() {
        repo = new FileExperimentRepository(tempDir);
        service = new ExperimentService(repo, (MonotonicClock) System::nanoTime);
        captured = new CopyOnWriteArrayList<>();
        service.addEventListener(captured::add);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    /** 两体初始相距 1e6 m（低于 5ε=5e6），速度让其在几步内退出 1.25x 迟滞阈值。 */
    private SimulationConfig exitingConfig() {
        return new SimulationConfig(
                "近遇-退出",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 1.0e20, Vector3.ZERO, Vector3.ZERO),
                        new BodySpec("b", "乙", "#4d96ff", 1.0e20,
                                Vector3.of(1.0e6, 0, 0), Vector3.of(2000, 0, 0))),
                600.0,
                PhysicalConstants.GRAVITATIONAL_CONSTANT,
                1.0e6,
                50L,
                null);
    }

    /** 两体相距 1e6 m 且相对速度很小，模拟期间持续在阈值内，便于验证暂停定稿。 */
    private SimulationConfig stayingConfig() {
        return new SimulationConfig(
                "近遇-停留",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 1.0e20, Vector3.ZERO, Vector3.ZERO),
                        new BodySpec("b", "乙", "#4d96ff", 1.0e20,
                                Vector3.of(1.0e6, 0, 0), Vector3.of(0, 1.0, 0))),
                600.0,
                PhysicalConstants.GRAVITATIONAL_CONSTANT,
                1.0e6,
                500_000L,
                null);
    }

    private boolean waitUntil(String desc, long timeoutMs,
            java.util.function.Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) return true;
            Thread.sleep(40);
        }
        System.err.println("[TEST] 等待超时: " + desc);
        return false;
    }

    private List<SimulationEvent> nearEncounterEvents() {
        return captured.stream()
                .filter(m -> m.type() == com.threebody.app.event.ExperimentMessageType.NEAR_ENCOUNTER)
                .map(m -> (SimulationEvent) m.payload())
                .toList();
    }

    @Test
    @DisplayName("近遇跨阈值后退出：产生 ENTER 与 FINAL，eventId 稳定且携带最近点")
    void enterThenFinalKeepsStableEventId() throws Exception {
        Experiment e = service.createExperiment("近遇退出", exitingConfig());
        assertTrue(waitUntil("完成", 8_000,
                () -> service.getExperiment(e.id()).status() == ExperimentStatus.COMPLETED));

        List<SimulationEvent> events = nearEncounterEvents();
        assertTrue(events.size() >= 2, "应至少有 ENTER 与 FINAL，实际 " + events.size());

        SimulationEvent enter = events.stream()
                .filter(ev -> ev.phase() == EventPhase.ENTER).findFirst().orElse(null);
        SimulationEvent fin = events.stream()
                .filter(ev -> ev.phase() == EventPhase.FINAL).findFirst().orElse(null);
        assertNotNull(enter, "应有 ENTER");
        assertNotNull(fin, "应有 FINAL");
        assertNotNull(enter.eventId(), "ENTER 必须带 eventId");
        assertEquals(enter.eventId(), fin.eventId(), "同一次近遇的 eventId 必须稳定");
        assertEquals(enter.sequence(), fin.sequence(), "UPDATE/FINAL 不得重新分配逻辑事件序号");
        assertNotNull(fin.closestDistanceMeters(), "FINAL 必须携带真实最近距离");
        assertNotNull(fin.closestStep(), "FINAL 必须携带最近步");
        assertTrue(fin.closestDistanceMeters() < fin.thresholdMeters(),
                "最近距离应低于触发阈值");
        assertTrue(enter.message().contains("甲") && enter.message().contains("乙"),
                "近遇消息应使用天体名称而非 UUID");
    }

    @Test
    @DisplayName("暂停时对活动近遇定稿并发布 FINAL")
    void pauseFinalizesActiveEncounter() throws Exception {
        Experiment e = service.createExperiment("近遇暂停", stayingConfig());
        assertTrue(waitUntil("出现 ENTER 事件", 8_000,
                () -> nearEncounterEvents().stream()
                        .anyMatch(ev -> ev.phase() == EventPhase.ENTER)));

        service.submitAction(e.id(), ExperimentAction.PAUSE, null);
        assertTrue(waitUntil("暂停完成", 5_000,
                () -> service.getExperiment(e.id()).status() == ExperimentStatus.PAUSED));

        assertTrue(waitUntil("出现 FINAL 事件", 5_000,
                () -> nearEncounterEvents().stream()
                        .anyMatch(ev -> ev.phase() == EventPhase.FINAL)));
        SimulationEvent fin = nearEncounterEvents().stream()
                .filter(ev -> ev.phase() == EventPhase.FINAL).findFirst().orElse(null);
        assertNotNull(fin, "暂停必须对活动近遇定稿 FINAL");
        assertNotNull(fin.closestDistanceMeters(), "FINAL 必须携带真实最近距离");
    }

    @Test
    @DisplayName("epsilon=0 时不产生近遇事件")
    void zeroSofteningProducesNoEncounters() throws Exception {
        SimulationConfig config = new SimulationConfig(
                "零软化近遇", exitingConfig().bodies(), 600.0,
                PhysicalConstants.GRAVITATIONAL_CONSTANT, 0.0, 50L, null);
        Experiment e = service.createExperiment("零软化", config);
        assertTrue(waitUntil("完成", 8_000,
                () -> service.getExperiment(e.id()).status() == ExperimentStatus.COMPLETED));
        assertTrue(nearEncounterEvents().isEmpty(), "epsilon=0 不应产生近遇事件");
    }
}
