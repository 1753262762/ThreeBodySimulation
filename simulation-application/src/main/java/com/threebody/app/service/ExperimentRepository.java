package com.threebody.app.service;

import com.threebody.app.domain.Experiment;
import com.threebody.core.SimulationState;

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
}
