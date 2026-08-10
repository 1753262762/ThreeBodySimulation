package com.threebody.app.service.persistence;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.threebody.app.domain.EndReason;
import com.threebody.app.domain.Experiment;
import com.threebody.app.domain.ExperimentMetrics;
import com.threebody.app.domain.ExperimentStatus;
import com.threebody.app.domain.SimulationEvent;
import com.threebody.app.domain.SimulationEventType;
import com.threebody.app.domain.TrajectoryInfo;
import com.threebody.app.service.ExperimentRepository;
import com.threebody.core.BodySpec;
import com.threebody.core.BodyState;
import com.threebody.core.SimulationConfig;
import com.threebody.core.SimulationState;
import com.threebody.core.Vector3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 基于 JSON 文件的实验持久化实现。
 *
 * <p>
 * Windows 数据目录为 {@code %LOCALAPPDATA%/ThreeBodyLab}，
 * 其他平台回退到 {@code ${user.home}/.threebody-lab}。
 * 使用临时文件加原子替换写入；损坏文件隔离至 {@code .corrupted/}。
 * </p>
 */
public class FileExperimentRepository implements ExperimentRepository {

    private static final String DATA_DIR_NAME = "ThreeBodyLab";
    private static final String EXPERIMENTS_FILE = "experiments.json";
    private static final String CORRUPTED_DIR = ".corrupted";

    private final Path dataDir;
    private final ObjectMapper mapper;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    public FileExperimentRepository() {
        this.dataDir = resolveDataDir();
        this.mapper = createObjectMapper();
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建数据目录：" + dataDir, e);
        }
    }

    public FileExperimentRepository(Path dataDir) {
        this.dataDir = dataDir;
        this.mapper = createObjectMapper();
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建数据目录：" + dataDir, e);
        }
    }

    static Path resolveDataDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Paths.get(localAppData, DATA_DIR_NAME);
        }
        return Paths.get(System.getProperty("user.home"), ".threebody-lab");
    }

    public Path dataDir() {
        return dataDir;
    }

    // ============================ 读 ============================

    @Override
    public List<Experiment> listAll() {
        rwLock.readLock().lock();
        try {
            return listAllInternal();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    private List<Experiment> listAllInternal() {
        Path manifest = dataDir.resolve(EXPERIMENTS_FILE);
        if (!Files.isRegularFile(manifest)) {
            return List.of();
        }
        try {
            String json = Files.readString(manifest);
            ExperimentList list = mapper.readValue(json, ExperimentList.class);
            return list.experiments != null ? list.experiments : List.of();
        } catch (IOException e) {
            handleCorruptedManifest(manifest, e);
            return List.of();
        }
    }

    // ============================ 写 ============================

    @Override
    public void save(Experiment experiment) {
        rwLock.writeLock().lock();
        try {
            List<Experiment> all = new ArrayList<>(listAllInternal());
            boolean found = false;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id().equals(experiment.id())) {
                    all.set(i, experiment);
                    found = true;
                    break;
                }
            }
            if (!found) {
                all.add(experiment);
            }
            // 保持与内存队列一致的顺序
            writeAll(all);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // ============================ 删 ============================

    @Override
    public long delete(String id) {
        rwLock.writeLock().lock();
        try {
            List<Experiment> all = new ArrayList<>(listAllInternal());
            long freedBytes = storageBytesInternal(id);
            all.removeIf(e -> e.id().equals(id));
            writeAll(all);

            // 删除关联的轨迹文件（如果存在）
            Path trajFile = dataDir.resolve("trajectory-" + id + ".json");
            try {
                if (Files.exists(trajFile)) {
                    freedBytes += Files.size(trajFile);
                    Files.deleteIfExists(trajFile);
                }
            } catch (IOException ignored) {
            }

            return freedBytes;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public long storageBytes(String id) {
        rwLock.readLock().lock();
        try {
            return storageBytesInternal(id);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    private long storageBytesInternal(String id) {
        Path manifest = dataDir.resolve(EXPERIMENTS_FILE);
        long bytes = 0;
        try {
            if (Files.exists(manifest)) {
                bytes += Files.size(manifest) / Math.max(1, listAllInternal().size());
            }
        } catch (IOException ignored) {
        }
        Path trajFile = dataDir.resolve("trajectory-" + id + ".json");
        try {
            if (Files.exists(trajFile)) {
                bytes += Files.size(trajFile);
            }
        } catch (IOException ignored) {
        }
        return bytes;
    }

    // ============================ 轨迹持久化 ============================

    @Override
    public void appendTrajectoryPoint(String experimentId, SimulationState state, long pointLimit) {
        Path trajFile = dataDir.resolve("trajectory-" + experimentId + ".json");
        rwLock.writeLock().lock();
        try {
            // 将当前状态序列化为一条 JSON 行（JSON Lines 格式，便于追加）
            TrajectoryPointRecord record = TrajectoryPointRecord.from(state);
            String line = mapper.writeValueAsString(record) + System.lineSeparator();
            Files.writeString(trajFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            // 检查是否需要降采样：超过 pointLimit 时减半
            long lineCount = countTrajectoryLines(trajFile);
            if (lineCount > pointLimit) {
                downsampleTrajectory(trajFile, pointLimit / 2);
            }
        } catch (IOException e) {
            System.err.println("[ThreeBodyLab] 轨迹归档写入失败：" + e.getMessage());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public List<SimulationState> loadTrajectory(String experimentId) {
        Path trajFile = dataDir.resolve("trajectory-" + experimentId + ".json");
        rwLock.readLock().lock();
        try {
            if (!Files.isRegularFile(trajFile)) {
                return List.of();
            }
            List<String> lines = Files.readAllLines(trajFile);
            List<SimulationState> states = new ArrayList<>(lines.size());
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    TrajectoryPointRecord record = mapper.readValue(line, TrajectoryPointRecord.class);
                    states.add(record.toSimulationState());
                } catch (IOException e) {
                    // 跳过损坏行
                    System.err.println("[ThreeBodyLab] 跳过损坏的轨迹行：" + e.getMessage());
                }
            }
            return Collections.unmodifiableList(states);
        } catch (IOException e) {
            System.err.println("[ThreeBodyLab] 轨迹文件读取失败：" + e.getMessage());
            return List.of();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    private static long countTrajectoryLines(Path trajFile) throws IOException {
        try (var lines = Files.lines(trajFile)) {
            return lines.count();
        }
    }

    /**
     * 降采样：保留每隔一行的数据，将文件行数减少至目标值附近。
     */
    private void downsampleTrajectory(Path trajFile, long targetCount) throws IOException {
        List<String> lines = Files.readAllLines(trajFile);
        if (lines.size() <= targetCount) return;
        List<String> sampled = new ArrayList<>();
        long stride = Math.max(2, lines.size() / (int) targetCount);
        for (int i = 0; i < lines.size(); i += (int) stride) {
            sampled.add(lines.get(i));
        }
        Path tmp = dataDir.resolve(trajFile.getFileName() + ".tmp");
        Files.writeString(tmp, String.join("", sampled));
        try {
            Files.move(tmp, trajFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, trajFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ============================ 内部 ============================

    private void writeAll(List<Experiment> experiments) {
        Path manifest = dataDir.resolve(EXPERIMENTS_FILE);
        Path tmp = dataDir.resolve(EXPERIMENTS_FILE + ".tmp");
        try {
            ExperimentList list = new ExperimentList();
            list.experiments = experiments;
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(list);
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, manifest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw new UncheckedIOException("无法写入实验清单：" + manifest, e);
        }
    }

    private void handleCorruptedManifest(Path manifest, IOException cause) {
        Path corruptedDir = dataDir.resolve(CORRUPTED_DIR);
        try {
            Files.createDirectories(corruptedDir);
            String timestamp = java.time.Instant.now().toString().replace(":", "-");
            Path target = corruptedDir.resolve(EXPERIMENTS_FILE + "." + timestamp);
            Files.move(manifest, target, StandardCopyOption.ATOMIC_MOVE);
            System.err.println("[ThreeBodyLab] 实验清单已损坏，已隔离至：" + target + " 原因：" + cause.getMessage());
        } catch (IOException moveFailed) {
            System.err.println("[ThreeBodyLab] 无法隔离损坏的实验清单：" + moveFailed.getMessage());
        }
    }

    // ============================ JSON 序列化 ============================

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
                .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, false)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();

        // 增大嵌套深度与字符串长度限制，兼容大型实验数据
        mapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder()
                        .maxNestingDepth(2000)
                        .maxStringLength(50_000_000)
                        .maxNumberLength(2000)
                        .build());

        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new Jdk8Module());
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

        // 序列化时使用简单字段名（无需注解）
        return mapper;
    }

    /**
     * 拓扑排序：先写实验数组中最独立的对象。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ExperimentList {
        public List<Experiment> experiments = new ArrayList<>();
    }

    /**
     * 归档轨迹点的 JSON 序列化载体。
     * 使用简单字段以减少 JSON 体积。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TrajectoryPointRecord {
        public long step;
        public double timeSeconds;
        public List<BodyRecord> bodies;

        static TrajectoryPointRecord from(SimulationState state) {
            TrajectoryPointRecord r = new TrajectoryPointRecord();
            r.step = state.step();
            r.timeSeconds = state.simulationTimeSeconds();
            r.bodies = state.bodies().stream().map(BodyRecord::from).toList();
            return r;
        }

        SimulationState toSimulationState() {
            List<BodyState> bodyStates = bodies.stream().map(BodyRecord::toBodyState).toList();
            return new SimulationState(step, timeSeconds, bodyStates);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class BodyRecord {
        public String id;
        public double px, py, pz;
        public double vx, vy, vz;

        static BodyRecord from(BodyState b) {
            BodyRecord r = new BodyRecord();
            r.id = b.id();
            r.px = b.position().x();
            r.py = b.position().y();
            r.pz = b.position().z();
            r.vx = b.velocity().x();
            r.vy = b.velocity().y();
            r.vz = b.velocity().z();
            return r;
        }

        BodyState toBodyState() {
            return new BodyState(id, new Vector3(px, py, pz), new Vector3(vx, vy, vz));
        }
    }
}
