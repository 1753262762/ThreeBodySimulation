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
     * 读取实验的所有归档轨迹点。
     *
     * @param experimentId 实验 ID
     * @return 归档轨迹点列表，按步数升序排列
     */
    List<SimulationState> loadTrajectory(String experimentId);
}
