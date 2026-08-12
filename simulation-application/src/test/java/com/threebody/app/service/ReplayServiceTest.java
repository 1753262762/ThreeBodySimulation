package com.threebody.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.threebody.app.domain.ExperimentAction;
import com.threebody.app.domain.ExperimentStatus;
import com.threebody.app.domain.ReplayJob;
import com.threebody.app.domain.ReplayJobStatus;
import com.threebody.app.domain.ReplaySource;
import com.threebody.app.service.persistence.FileExperimentRepository;
import com.threebody.core.BodySpec;
import com.threebody.core.PhysicalConstants;
import com.threebody.core.SimulationConfig;
import com.threebody.core.Vector3;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** B6 精确回放服务测试。 */
class ReplayServiceTest {

    @TempDir
    Path tempDir;

    private FileExperimentRepository repo;
    private ExperimentService experimentService;
    private ReplayService replayService;

    @BeforeEach
    void setUp() {
        repo = new FileExperimentRepository(tempDir);
        experimentService = new ExperimentService(repo, (MonotonicClock) System::nanoTime);
        replayService = new ReplayService(experimentService, repo);
    }

    @AfterEach
    void tearDown() {
        replayService.close();
        experimentService.close();
    }

    private SimulationConfig config() {
        return new SimulationConfig(
                "回放测试",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 1.0e30, Vector3.of(-1.0e11, 0, 0), Vector3.of(0, -1.3e4, 0)),
                        new BodySpec("b", "乙", "#4d96ff", 1.0e30, Vector3.of(1.0e11, 0, 0), Vector3.of(0, 1.3e4, 0))),
                3600.0, PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0e7, 200L, null);
    }

    /** 长步数配置：归档采样步长 >1，产生需要重算的中间步。 */
    private SimulationConfig longConfig() {
        return new SimulationConfig(
                "回放长配置",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 1.0e30, Vector3.of(-1.0e11, 0, 0), Vector3.of(0, -1.3e4, 0)),
                        new BodySpec("b", "乙", "#4d96ff", 1.0e30, Vector3.of(1.0e11, 0, 0), Vector3.of(0, 1.3e4, 0))),
                3600.0, PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0e7, 200_000L, null);
    }

    /**
     * 超长步数配置（仅用于队列满测试）：清空归档后首个任务需从初始状态重算数百万步、
     * 耗时数秒，保证 worker 在提交 8 个填充任务期间保持忙碌，队列稳定堆满（避免竞态 flaky）。
     */
    private SimulationConfig hugeConfig() {
        return new SimulationConfig(
                "回放超长配置",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 1.0e30, Vector3.of(-1.0e11, 0, 0), Vector3.of(0, -1.3e4, 0)),
                        new BodySpec("b", "乙", "#4d96ff", 1.0e30, Vector3.of(1.0e11, 0, 0), Vector3.of(0, 1.3e4, 0))),
                3600.0, PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0e7, 3_000_000L, null);
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

    @Test
    @DisplayName("目标等于当前权威状态时 POST 返回 CURRENT_STATE/COMPLETED")
    void exactCurrentState() throws Exception {
        com.threebody.app.domain.Experiment e = experimentService.createExperiment("回放", config());
        assertTrue(waitUntil("运行完成", 8_000,
                () -> experimentService.getExperiment(e.id()).status() == ExperimentStatus.COMPLETED));

        long currentStep = experimentService.getExperiment(e.id()).step();
        ReplayJob job = replayService.create(e.id(), currentStep);
        assertEquals(ReplayJobStatus.COMPLETED, job.status());
        assertEquals(ReplaySource.CURRENT_STATE, job.source());
        assertEquals(1.0, job.progress());
        assertNotNull(job.result());
    }

    @Test
    @DisplayName("目标在已归档范围内且无精确点时从 floor 重算返回 RECOMPUTED")
    void recomputeFromFloor() throws Exception {
        com.threebody.app.domain.Experiment e = experimentService.createExperiment("回放", longConfig());
        assertTrue(waitUntil("运行完成", 15_000,
                () -> experimentService.getExperiment(e.id()).status() == ExperimentStatus.COMPLETED));
        long currentStep = experimentService.getExperiment(e.id()).step();

        // 归档采样步长 >1 时，中间步没有精确点，触发重算
        long target = Math.max(1L, currentStep - 1L);
        ReplayJob job = replayService.create(e.id(), target);
        assertEquals(ReplayJobStatus.QUEUED, job.status());
        assertTrue(waitUntil("重算完成", 15_000,
                () -> replayService.get(job.jobId()).status() == ReplayJobStatus.COMPLETED));
        ReplayJob completed = replayService.get(job.jobId());
        assertEquals(ReplaySource.RECOMPUTED, completed.source());
        assertNotNull(completed.result());
        assertEquals(target, completed.result().step(), "重算结果应到达目标步");
    }

    @Test
    @DisplayName("非法目标步抛出 400 语义异常")
    void invalidTargetRejected() {
        com.threebody.app.domain.Experiment e = experimentService.createExperiment("回放", config());
        assertThrows(IllegalArgumentException.class, () -> replayService.create(e.id(), -1L));
    }

    @Test
    @DisplayName("待处理任务达到 8 个上限后返回队列满")
    void queueFullWhenExceedingCapacity() throws Exception {
        com.threebody.app.domain.Experiment e = experimentService.createExperiment("回放", hugeConfig());
        assertTrue(waitUntil("运行完成", 20_000,
                () -> experimentService.getExperiment(e.id()).status() == ExperimentStatus.COMPLETED));
        long currentStep = experimentService.getExperiment(e.id()).step();
        // 清空归档，使每个任务都需从初始状态重算较远步，确保 worker 忙碌时队列保持满
        repo.resetTrajectory(e.id());

        // 第一个任务占据 worker，其余任务入队
        replayService.create(e.id(), currentStep - 1L);
        for (int i = 1; i <= ReplayService.MAX_PENDING_JOBS; i++) {
            replayService.create(e.id(), Math.max(1L, currentStep - 1L - i));
        }
        assertThrows(ReplayService.ReplayQueueFullException.class,
                () -> replayService.create(e.id(), 1L));
    }

    @Test
    @DisplayName("回放任务不修改实验权威状态")
    void replayDoesNotMutateAuthoritativeState() throws Exception {
        com.threebody.app.domain.Experiment e = experimentService.createExperiment("回放", config());
        assertTrue(waitUntil("运行完成", 8_000,
                () -> experimentService.getExperiment(e.id()).status() == ExperimentStatus.COMPLETED));
        long currentStep = experimentService.getExperiment(e.id()).step();

        int eventsBefore = experimentService.getExperiment(e.id()).events().size();
        ReplayJob job = replayService.create(e.id(), Math.max(1L, currentStep - 1L));
        assertEquals(ReplayJobStatus.COMPLETED, job.status());

        assertEquals(currentStep, experimentService.getExperiment(e.id()).step(), "权威步不得改变");
        assertEquals(eventsBefore, experimentService.getExperiment(e.id()).events().size(),
                "回放不得写入事件");
        assertEquals(ExperimentStatus.COMPLETED, experimentService.getExperiment(e.id()).status(),
                "回放不得改变实验状态");
    }

    @Test
    @DisplayName("RESTART 递增运行代次使旧回放任务失效")
    void restartInvalidatesGeneration() throws Exception {
        com.threebody.app.domain.Experiment e = experimentService.createExperiment("回放", config());
        assertTrue(waitUntil("运行完成", 8_000,
                () -> experimentService.getExperiment(e.id()).status() == ExperimentStatus.COMPLETED));
        long before = experimentService.runGeneration(e.id());
        experimentService.submitAction(e.id(), ExperimentAction.RESTART, null);
        assertTrue(experimentService.runGeneration(e.id()) > before, "RESTART 应递增运行代次");
    }

    @Test
    @DisplayName("删除后任务查询返回未找到")
    void deletedExperimentJobNotFound() {
        com.threebody.app.domain.Experiment e = experimentService.createExperiment("回放", config());
        experimentService.deleteExperiment(e.id());
        assertThrows(ReplayService.ReplayJobNotFoundException.class,
                () -> replayService.get("missing"));
    }
}
