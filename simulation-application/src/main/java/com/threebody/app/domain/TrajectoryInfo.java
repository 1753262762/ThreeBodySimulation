package com.threebody.app.domain;

/**
 * 轨迹归档信息。
 *
 * @param sampleStride   归档采样当前步长
 * @param sampleCount    已归档采样点数量
 * @param pointLimit     归档采样点上限，固定为 50000
 * @param liveWindowSize 实时画布每个天体保留的最近点数，固定为 2000
 */
public record TrajectoryInfo(
        long sampleStride,
        long sampleCount,
        long pointLimit,
        int liveWindowSize) {
}