package com.threebody.app.service;

import com.threebody.app.domain.Experiment;
import com.threebody.core.SimulationState;

import java.util.ArrayList;
import java.util.List;

/**
 * 实验持久化接口。实现类负责 JSON 序列化与文件存储；
 * 调用方不关心底层存储细节。
 */
public interface ExperimentRepository {

    /** 返回所有已持久化的实验列表。 */
    List<Experiment> listAll();

    /** 持久化单个实验（创建或更新）。 */
    void save(Experiment experiment);

    /** 删除实验及其关联数据文件，返回释放的字节数。 */
    long delete(String id);

    /** 查询指定实验所占用的存储字节数。 */
    long storageBytes(String id);

    /**
     * 追加一条轨迹归档点到实验的轨迹文件中。
     *
     * @param experimentId 实验 ID
     * @param state        当前模拟状态（含步数和所有天体状态）
     * @param pointLimit   归档点上限，超过时进行降采样
     */
    void appendTrajectoryPoint(String experimentId, SimulationState state, long pointLimit);

    /**
     * Batch variant used by the background archive writer. Implementations
     * should append all points in one storage operation. The default keeps
     * compatibility with small/in-memory repositories.
     */
    default void appendTrajectoryPoints(String experimentId, List<SimulationState> states, long pointLimit) {
        for (SimulationState state : states) {
            appendTrajectoryPoint(experimentId, state, pointLimit);
        }
    }

    /**
     * Atomically replaces an archive after deterministic compression.
     * Implementations that support archival compression must override this
     * method.  Silently appending a replacement would duplicate old points
     * and make subsequent reads non-deterministic, so legacy repositories fail
     * explicitly instead.
     */
    default void replaceTrajectoryPoints(String experimentId, List<SimulationState> states) {
        throw new UnsupportedOperationException("atomic trajectory replacement is not supported");
    }

    /** Flushes pending archive writes for one experiment, if any. */
    default void flushTrajectory(String experimentId) {
    }

    /** Flushes all pending archive writes. */
    default void flushAllTrajectories() {
    }

    /** Removes an archive without deleting the experiment manifest entry. */
    default void resetTrajectory(String experimentId) {
    }

    /**
     * 读取实验的所有归档轨迹点。
     *
     * @param experimentId 实验 ID
     * @return 归档轨迹点列表，按步数升序排列
     */
    List<SimulationState> loadTrajectory(String experimentId);

    /**
     * 流式读取落在闭区间 [fromStep, toStep] 内的已持久化归档点，按 step 升序。
     * 超过 maxPoints 时均匀抽样并保留区间首尾。实现必须使用读锁与流式扫描，
     * 不得调用 Files.readAllLines，不得复用持写锁的 loadTrajectory()。
     *
     * @param archiveSampleStride 归档当前采样步长，由调用方从实验详情传入
     */
    default HistorySlice readTrajectoryRange(String experimentId, long fromStep, long toStep, int maxPoints,
            long archiveSampleStride) {
        List<SimulationState> all = loadTrajectory(experimentId);
        Long availableFrom = null;
        Long availableTo = null;
        List<SimulationState> inRange = new ArrayList<>();
        for (SimulationState state : all) {
            if (availableFrom == null || state.step() < availableFrom) availableFrom = state.step();
            if (availableTo == null || state.step() > availableTo) availableTo = state.step();
            if (state.step() >= fromStep && state.step() <= toStep) {
                inRange.add(state);
            }
        }
        boolean downsampled = inRange.size() > maxPoints;
        List<SimulationState> points = downsampled ? sample(inRange, Math.max(2, maxPoints)) : inRange;
        return new HistorySlice(points, availableFrom, availableTo, Math.max(1L, archiveSampleStride), downsampled);
    }

    /** 查询归档中与 targetStep 完全相等的持久化点；不存在返回 empty。 */
    default java.util.Optional<SimulationState> findTrajectoryAtStep(String experimentId, long targetStep) {
        return loadTrajectory(experimentId).stream()
                .filter(state -> state.step() == targetStep)
                .findFirst();
    }

    /** 查询归档中不大于 targetStep 的最近持久化点（floor）；不存在返回 empty。 */
    default java.util.Optional<SimulationState> findTrajectoryAtOrBefore(String experimentId, long targetStep) {
        SimulationState floor = null;
        for (SimulationState state : loadTrajectory(experimentId)) {
            if (state.step() > targetStep) {
                break;
            }
            floor = state;
        }
        return floor == null ? java.util.Optional.empty() : java.util.Optional.of(floor);
    }

    private static List<SimulationState> sample(List<SimulationState> points, int target) {
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
}
