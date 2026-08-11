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
import com.threebody.app.domain.Experiment;
import com.threebody.app.service.ExperimentRepository;
import com.threebody.core.BodyState;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** File-backed experiment and JSONL trajectory repository. */
public class FileExperimentRepository implements ExperimentRepository {

    private static final String DATA_DIR_NAME = "ThreeBodyLab";
    private static final String EXPERIMENTS_FILE = "experiments.json";
    private static final String CORRUPTED_DIR = ".corrupted";

    private final Path dataDir;
    private final ObjectMapper mapper;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    /** Per-experiment metadata; the trajectory body is never retained here. */
    private final Map<String, Long> trajectoryCounts = new ConcurrentHashMap<>();
    private final Set<String> trajectoryMetadataLoaded = ConcurrentHashMap.newKeySet();

    public FileExperimentRepository() {
        this(resolveDataDir());
    }

    public FileExperimentRepository(Path dataDir) {
        this.dataDir = dataDir;
        this.mapper = createObjectMapper();
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("unable to create data directory " + dataDir, e);
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
            ExperimentList list = mapper.readValue(Files.readString(manifest), ExperimentList.class);
            return list.experiments != null ? list.experiments : List.of();
        } catch (IOException e) {
            handleCorruptedManifest(manifest, e);
            return List.of();
        }
    }

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
            writeAll(all);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public long delete(String id) {
        rwLock.writeLock().lock();
        try {
            List<Experiment> all = new ArrayList<>(listAllInternal());
            long freedBytes = storageBytesInternal(id);
            all.removeIf(experiment -> experiment.id().equals(id));
            writeAll(all);
            Path trajectory = trajectoryPath(id);
            try {
                if (Files.exists(trajectory)) {
                    freedBytes += Files.size(trajectory);
                    Files.deleteIfExists(trajectory);
                }
            } catch (IOException ignored) {
                // Deletion remains best-effort for accounting compatibility.
            }
            trajectoryCounts.remove(id);
            trajectoryMetadataLoaded.remove(id);
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
        long bytes = 0;
        Path manifest = dataDir.resolve(EXPERIMENTS_FILE);
        try {
            if (Files.exists(manifest)) {
                bytes += Files.size(manifest) / Math.max(1, listAllInternal().size());
            }
            Path trajectory = trajectoryPath(id);
            if (Files.exists(trajectory)) {
                bytes += Files.size(trajectory);
            }
        } catch (IOException ignored) {
        }
        return bytes;
    }

    @Override
    public void appendTrajectoryPoint(String experimentId, SimulationState state, long pointLimit) {
        appendTrajectoryPoints(experimentId, List.of(state), pointLimit);
    }

    @Override
    public void appendTrajectoryPoints(String experimentId, List<SimulationState> states, long pointLimit) {
        if (states == null || states.isEmpty()) {
            return;
        }
        rwLock.writeLock().lock();
        try {
            long currentCount = ensureTrajectoryMetadata(experimentId);
            List<SimulationState> validStates = states.stream().filter(state -> state != null).toList();
            if (validStates.isEmpty()) {
                return;
            }
            writeTrajectoryLines(experimentId, validStates, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            currentCount += validStates.size();
            trajectoryCounts.put(experimentId, currentCount);
            if (currentCount > Math.max(1L, pointLimit)) {
                List<SimulationState> all = readTrajectoryFile(experimentId);
                long limit = Math.max(1L, pointLimit);
                int target = limit == 1L ? 1
                        : (int) Math.min((long) all.size(), Math.max(2L, limit / 2L));
                replaceTrajectoryPointsInternal(experimentId, uniformlySample(all, target));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("trajectory archive append failed", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void replaceTrajectoryPoints(String experimentId, List<SimulationState> states) {
        rwLock.writeLock().lock();
        try {
            replaceTrajectoryPointsInternal(experimentId, states == null ? List.of() : states);
        } catch (IOException e) {
            throw new UncheckedIOException("trajectory archive replace failed", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void flushTrajectory(String experimentId) {
        // File writes are synchronous; this method completes the common API.
    }

    @Override
    public void flushAllTrajectories() {
        // File writes are synchronous; this method completes the common API.
    }

    @Override
    public void resetTrajectory(String experimentId) {
        rwLock.writeLock().lock();
        try {
            Files.deleteIfExists(trajectoryPath(experimentId));
            trajectoryCounts.remove(experimentId);
            trajectoryMetadataLoaded.remove(experimentId);
        } catch (IOException e) {
            throw new UncheckedIOException("trajectory archive reset failed", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public List<SimulationState> loadTrajectory(String experimentId) {
        rwLock.writeLock().lock();
        try {
            List<SimulationState> states = readTrajectoryFile(experimentId);
            trajectoryCounts.put(experimentId, (long) states.size());
            trajectoryMetadataLoaded.add(experimentId);
            return List.copyOf(states);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private long ensureTrajectoryMetadata(String experimentId) throws IOException {
        if (trajectoryMetadataLoaded.contains(experimentId)) {
            return trajectoryCounts.getOrDefault(experimentId, 0L);
        }
        List<SimulationState> states = readTrajectoryFile(experimentId);
        long count = states.size();
        trajectoryCounts.put(experimentId, count);
        trajectoryMetadataLoaded.add(experimentId);
        return count;
    }

    private List<SimulationState> readTrajectoryFile(String experimentId) {
        Path trajectory = trajectoryPath(experimentId);
        if (!Files.isRegularFile(trajectory)) {
            return new ArrayList<>();
        }
        try {
            List<SimulationState> states = new ArrayList<>();
            for (String line : Files.readAllLines(trajectory)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    states.add(mapper.readValue(line, TrajectoryPointRecord.class).toSimulationState());
                } catch (IOException malformed) {
                    System.err.println("[ThreeBodyLab] skipped malformed trajectory line: "
                            + malformed.getMessage());
                }
            }
            return states;
        } catch (IOException e) {
            throw new UncheckedIOException("trajectory archive read failed", e);
        }
    }

    private void replaceTrajectoryPointsInternal(String experimentId, List<SimulationState> states)
            throws IOException {
        List<SimulationState> replacement = new ArrayList<>(states);
        Path trajectory = trajectoryPath(experimentId);
        Path temporary = dataDir.resolve(trajectory.getFileName() + ".tmp");
        StringBuilder content = new StringBuilder();
        for (SimulationState state : replacement) {
            content.append(mapper.writeValueAsString(TrajectoryPointRecord.from(state)))
                    .append(System.lineSeparator());
        }
        Files.writeString(temporary, content.toString(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, trajectory, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.deleteIfExists(temporary);
            throw unsupported;
        }
        trajectoryCounts.put(experimentId, (long) replacement.size());
        trajectoryMetadataLoaded.add(experimentId);
    }

    private void writeTrajectoryLines(String experimentId, List<SimulationState> states,
            StandardOpenOption... options) throws IOException {
        StringBuilder content = new StringBuilder();
        for (SimulationState state : states) {
            content.append(mapper.writeValueAsString(TrajectoryPointRecord.from(state)))
                    .append(System.lineSeparator());
        }
        Files.writeString(trajectoryPath(experimentId), content.toString(), options);
    }

    private Path trajectoryPath(String experimentId) {
        return dataDir.resolve("trajectory-" + experimentId + ".json");
    }

    private static List<SimulationState> uniformlySample(List<SimulationState> points, int target) {
        if (target >= points.size()) {
            return new ArrayList<>(points);
        }
        if (target <= 1) {
            return points.isEmpty() ? List.of() : List.of(points.get(0));
        }
        List<SimulationState> sampled = new ArrayList<>(target);
        for (int i = 0; i < target; i++) {
            int index = (int) Math.round((double) i * (points.size() - 1) / (target - 1));
            sampled.add(points.get(index));
        }
        return sampled;
    }

    private void writeAll(List<Experiment> experiments) {
        Path manifest = dataDir.resolve(EXPERIMENTS_FILE);
        Path temporary = dataDir.resolve(EXPERIMENTS_FILE + ".tmp");
        try {
            ExperimentList list = new ExperimentList();
            list.experiments = experiments;
            Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(list),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(temporary, manifest, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, manifest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            throw new UncheckedIOException("unable to write experiment manifest", e);
        }
    }

    private void handleCorruptedManifest(Path manifest, IOException cause) {
        Path corruptedDir = dataDir.resolve(CORRUPTED_DIR);
        try {
            Files.createDirectories(corruptedDir);
            String timestamp = java.time.Instant.now().toString().replace(":", "-");
            Path target = corruptedDir.resolve(EXPERIMENTS_FILE + "." + timestamp);
            Files.move(manifest, target, StandardCopyOption.ATOMIC_MOVE);
            System.err.println("[ThreeBodyLab] moved corrupt manifest to " + target + ": "
                    + cause.getMessage());
        } catch (IOException moveFailed) {
            System.err.println("[ThreeBodyLab] unable to isolate corrupt manifest: "
                    + moveFailed.getMessage());
        }
    }

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
                .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, false)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(2000).maxStringLength(50_000_000)
                .maxNumberLength(2000).build());
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new Jdk8Module());
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        return mapper;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ExperimentList {
        public List<Experiment> experiments = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TrajectoryPointRecord {
        public long step;
        public double timeSeconds;
        public List<BodyRecord> bodies;

        static TrajectoryPointRecord from(SimulationState state) {
            TrajectoryPointRecord record = new TrajectoryPointRecord();
            record.step = state.step();
            record.timeSeconds = state.simulationTimeSeconds();
            record.bodies = state.bodies().stream().map(BodyRecord::from).toList();
            return record;
        }

        SimulationState toSimulationState() {
            List<BodyState> bodyStates = bodies == null ? List.of()
                    : bodies.stream().map(BodyRecord::toBodyState).toList();
            return new SimulationState(step, timeSeconds, bodyStates);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class BodyRecord {
        public String id;
        public double px, py, pz;
        public double vx, vy, vz;

        static BodyRecord from(BodyState body) {
            BodyRecord record = new BodyRecord();
            record.id = body.id();
            record.px = body.position().x();
            record.py = body.position().y();
            record.pz = body.position().z();
            record.vx = body.velocity().x();
            record.vy = body.velocity().y();
            record.vz = body.velocity().z();
            return record;
        }

        BodyState toBodyState() {
            return new BodyState(id, new Vector3(px, py, pz), new Vector3(vx, vy, vz));
        }
    }
}
