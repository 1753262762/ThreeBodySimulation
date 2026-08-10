package com.threebody.app.service.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.threebody.app.domain.Experiment;
import com.threebody.app.domain.ExperimentStatus;
import com.threebody.core.BodySpec;
import com.threebody.core.BodyState;
import com.threebody.core.PhysicalConstants;
import com.threebody.core.SimulationConfig;
import com.threebody.core.SimulationState;
import com.threebody.core.Vector3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 集成测试：验证 FileExperimentRepository 的核心行为。
 *
 * <p>覆盖修复项：#2（并发竞态）、#3（轨迹持久化）、#7（ATOMIC_MOVE 回退）。
 */
class FileExperimentRepositoryTest {

    @TempDir
    Path tempDir;

    private FileExperimentRepository repo;
    private Path expectedManifest;

    @BeforeEach
    void setUp() {
        repo = new FileExperimentRepository(tempDir);
        expectedManifest = tempDir.resolve("experiments.json");
    }

    @AfterEach
    void tearDown() {
        // TempDir 自动清理
    }

    private Experiment createTestExperiment(String id, String name) {
        SimulationConfig config = new SimulationConfig(
                "测试配置",
                List.of(
                        new BodySpec("sun", "太阳", "#ffd166", 1.98892e30,
                                Vector3.ZERO, Vector3.ZERO),
                        new BodySpec("earth", "地球", "#4d96ff", 5.972e24,
                                Vector3.of(1.496e11, 0, 0),
                                Vector3.of(0, 29783, 0))),
                3600.0,
                PhysicalConstants.GRAVITATIONAL_CONSTANT,
                1.0e6,
                100L,
                null);
        return new Experiment(id, name, config);
    }

    private SimulationState createTestState(long step, double timeSeconds) {
        List<BodyState> bodies = List.of(
                new BodyState("sun", Vector3.ZERO, Vector3.ZERO),
                new BodyState("earth",
                        Vector3.of(1.496e11 + step * 1e8, 0, 0),
                        Vector3.of(0, 29783 + step * 0.1, 0)));
        return new SimulationState(step, timeSeconds, bodies);
    }

    // ==================== 修复 #2：并发竞态 ====================

    @Test
    @DisplayName("并发 save() 无异常——写锁保护读写周期")
    void concurrentSaveDoesNotThrow() throws Exception {
        int threadCount = 4;
        int writesPerThread = 30;
        CountDownLatch readyLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount * writesPerThread; i++) {
            repo.save(createTestExperiment("exp-" + i, "实验 " + i));
        }

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    readyLatch.await();
                    for (int w = 0; w < writesPerThread; w++) {
                        Experiment exp = createTestExperiment(
                                "exp-" + (threadId * writesPerThread + w),
                                "更新 v" + threadId);
                        exp.setStatus(ExperimentStatus.PAUSED);
                        repo.save(exp);
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        assertEquals(0, errorCount.get(), "并发 save 不应抛出异常");

        // 验证文件存在且大小合理
        assertTrue(Files.isRegularFile(expectedManifest), "清单文件应存在");
        long fileSize = Files.size(expectedManifest);
        assertTrue(fileSize > 500, "清单文件应有足够内容，实际大小: " + fileSize);
    }

    @Test
    @DisplayName("读锁不阻塞读操作 — 并发读取无异常")
    void readLockDoesNotBlockReaders() throws Exception {
        int threadCount = 8;
        CountDownLatch readyLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < 50; i++) {
            repo.save(createTestExperiment("exp-" + i, "实验 " + i));
        }

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    readyLatch.await();
                    for (int i = 0; i < 100; i++) {
                        repo.listAll(); // 即使反序列化失败也不应抛异常
                        repo.storageBytes("exp-0");
                    }
                } catch (Exception e) {
                    // 不应有 NPE 或锁异常
                    e.printStackTrace();
                    fail("并发读取不应抛异常: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
    }

    // ==================== 修复 #3：轨迹持久化 ====================

    @Test
    @DisplayName("appendTrajectoryPoint 写入轨迹文件并可回读")
    void appendAndLoadTrajectory() throws Exception {
        String expId = "traj-test-1";

        for (int i = 0; i < 10; i++) {
            repo.appendTrajectoryPoint(expId, createTestState(i * 10, i * 3600.0), 50_000L);
        }

        Path trajFile = tempDir.resolve("trajectory-" + expId + ".json");
        assertTrue(Files.isRegularFile(trajFile), "轨迹文件应存在");

        List<SimulationState> loaded = repo.loadTrajectory(expId);
        assertEquals(10, loaded.size(), "应加载全部 10 个轨迹点");
        assertEquals(0L, loaded.get(0).step());
        assertEquals(0.0, loaded.get(0).simulationTimeSeconds(), 1e-9);
        assertEquals(90L, loaded.get(9).step());
        assertEquals(9 * 3600.0, loaded.get(9).simulationTimeSeconds(), 1e-9);

        // 验证天体数据
        for (SimulationState s : loaded) {
            assertEquals(2, s.bodies().size());
            assertTrue(s.bodies().get(0).position().isFinite());
            assertEquals("sun", s.bodies().get(0).id());
        }
    }

    @Test
    @DisplayName("轨迹降采样 — 超过 pointLimit 时自动减半")
    void trajectoryDownsamplesWhenExceedingLimit() throws Exception {
        String expId = "traj-downsample";
        long smallLimit = 10L;

        for (int i = 0; i < 25; i++) {
            repo.appendTrajectoryPoint(expId, createTestState(i, i * 100.0), smallLimit);
        }

        List<SimulationState> loaded = repo.loadTrajectory(expId);
        assertTrue(loaded.size() <= smallLimit + 2,
                "降采样后点数应不显著超限，实际: " + loaded.size());
        assertEquals(0L, loaded.get(0).step(), "首个点应保留");
        assertTrue(loaded.get(loaded.size() - 1).step() >= 20, "末尾附近点应保留");
    }

    @Test
    @DisplayName("空实验的轨迹加载返回空列表")
    void loadTrajectoryOfNonexistentReturnsEmpty() {
        List<SimulationState> loaded = repo.loadTrajectory("nonexistent");
        assertNotNull(loaded);
        assertTrue(loaded.isEmpty());
    }

    @Test
    @DisplayName("delete 同时删除轨迹文件")
    void deleteRemovesTrajectoryFile() throws Exception {
        String expId = "del-test";
        repo.save(createTestExperiment(expId, "待删除"));
        repo.appendTrajectoryPoint(expId, createTestState(0, 0), 50_000L);
        repo.appendTrajectoryPoint(expId, createTestState(10, 36000), 50_000L);

        Path trajFile = tempDir.resolve("trajectory-" + expId + ".json");
        assertTrue(Files.isRegularFile(trajFile));

        long freed = repo.delete(expId);
        assertTrue(freed > 0);
        assertFalse(Files.exists(trajFile), "轨迹文件应随实验一同删除");
    }

    // ==================== 修复 #7：ATOMIC_MOVE 回退 ====================

    @Test
    @DisplayName("save 写入清单文件（验证原子移动完成）")
    void saveCreatesManifestFile() throws Exception {
        repo.save(createTestExperiment("atomic-test", "原子移动测试"));

        assertTrue(Files.isRegularFile(expectedManifest),
                "清单文件应存在（验证 ATOMIC_MOVE 或回退成功）");

        // 验证清单文件包含实验 ID
        String content = Files.readString(expectedManifest);
        assertTrue(content.contains("atomic-test"),
                "清单文件应包含实验 ID");
    }

    @Test
    @DisplayName("save 后可完整反序列化实验清单")
    void saveAndListAllRoundTripsExperiment() {
        Experiment original = createTestExperiment("round-trip", "重启恢复测试");
        original.setStatus(ExperimentStatus.PAUSED);
        original.setState(createTestState(42L, 151_200.0));
        repo.save(original);

        List<Experiment> restored = repo.listAll();

        assertEquals(1, restored.size());
        Experiment loaded = restored.get(0);
        assertEquals(original.id(), loaded.id());
        assertEquals(original.name(), loaded.name());
        assertEquals(ExperimentStatus.PAUSED, loaded.status());
        assertNotNull(loaded.state());
        assertEquals(42L, loaded.state().step());
        assertFalse(Files.exists(tempDir.resolve(".corrupted")));
    }

    @Test
    @DisplayName("存储字节数报告正数值")
    void storageBytesPositive() {
        repo.save(createTestExperiment("size-test", "大小测试"));
        long bytes = repo.storageBytes("size-test");
        assertTrue(bytes > 0, "存储字节数应为正数");
    }

    @Test
    @DisplayName("不存在的实验 storageBytes 返回 0")
    void storageBytesMissing() {
        assertEquals(0L, repo.storageBytes("no-such-id"));
    }

    // ==================== 损坏隔离 ====================

    @Test
    @DisplayName("损坏的清单文件被隔离，listAll 返回空且不抛异常")
    void corruptedManifestIsQuarantined() throws Exception {
        Files.writeString(expectedManifest, "这不是合法的 JSON {{{");

        List<Experiment> all = repo.listAll();
        assertNotNull(all);
        assertTrue(all.isEmpty(), "损坏清单应返回空列表");
        assertFalse(Files.exists(expectedManifest), "损坏清单应已被移走");

        Path corruptedDir = tempDir.resolve(".corrupted");
        assertTrue(Files.isDirectory(corruptedDir), "应存在 .corrupted 隔离目录");
    }

    // ==================== 文件系统验证 ====================

    @Test
    @DisplayName("多次 save 后清单文件存在且大小递增")
    void manifestFileGrowsWithSaves() throws Exception {
        repo.save(createTestExperiment("a", "实验 A"));
        long size1 = Files.exists(expectedManifest) ? Files.size(expectedManifest) : 0;

        repo.save(createTestExperiment("b", "实验 B"));
        long size2 = Files.size(expectedManifest);

        repo.save(createTestExperiment("c", "实验 C"));
        long size3 = Files.size(expectedManifest);

        assertTrue(size3 >= size2, "多次写入后文件应增长或保持");
    }

    @Test
    @DisplayName("save 后清单文件为合法 JSON")
    void savedManifestIsValidJson() throws Exception {
        repo.save(createTestExperiment("json-test", "JSON 测试"));

        assertTrue(Files.isRegularFile(expectedManifest));
        String content = Files.readString(expectedManifest);

        // 基本 JSON 结构检查
        assertTrue(content.trim().startsWith("{"), "应为 JSON 对象");
        assertTrue(content.contains("experiments"), "应包含 experiments 键");
    }

    // ==================== 综合集成测试 ====================

    @Test
    @DisplayName("综合场景：创建→轨迹→删除，全流程文件系统验证")
    void fullLifecycleFileSystem() throws Exception {
        String expId = "lifecycle";

        // 1. 创建
        repo.save(createTestExperiment(expId, "生命周期测试"));
        assertTrue(Files.isRegularFile(expectedManifest));

        // 2. 追加轨迹
        for (int i = 0; i < 30; i++) {
            repo.appendTrajectoryPoint(expId, createTestState(i, i * 3600.0), 50_000L);
        }
        Path trajFile = tempDir.resolve("trajectory-" + expId + ".json");
        assertTrue(Files.isRegularFile(trajFile));
        assertTrue(Files.size(trajFile) > 100, "轨迹文件应有内容");

        // 3. 回读轨迹（SimulationState 是 record，可正常反序列化）
        List<SimulationState> traj = repo.loadTrajectory(expId);
        assertEquals(30, traj.size());
        assertEquals(0L, traj.get(0).step());
        assertEquals(29L, traj.get(29).step());

        // 4. 删除
        long freed = repo.delete(expId);
        assertTrue(freed > 0);
        assertFalse(Files.exists(trajFile), "轨迹文件应已删除");

        // 5. loadTrajectory 返回空
        assertTrue(repo.loadTrajectory(expId).isEmpty());
    }
}
