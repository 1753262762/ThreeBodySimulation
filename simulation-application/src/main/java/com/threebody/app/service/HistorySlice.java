package com.threebody.app.service;

import com.threebody.core.SimulationState;
import java.util.List;

/**
 * 历史轨迹范围读取结果。
 *
 * @param points            落在闭区间 [fromStep, toStep] 内的已持久化归档点，按 step 升序
 * @param availableFromStep 归档中实际可用的最小步，可能为 null（无归档）
 * @param availableToStep   归档中实际可用的最大步，可能为 null（无归档）
 * @param archiveSampleStride 归档当前采样步长
 * @param downsampled       是否因超过 maxPoints 而均匀抽样
 */
public record HistorySlice(
        List<SimulationState> points,
        Long availableFromStep,
        Long availableToStep,
        long archiveSampleStride,
        boolean downsampled) {

    public HistorySlice {
        points = points == null ? List.of() : List.copyOf(points);
    }
}
