package com.threebody.core;

/**
 * 近距离事件：两体距离小于阈值时记录，但不中止模拟，也不合并天体。
 *
 * @param firstBodyId     第一个天体标识
 * @param secondBodyId    第二个天体标识
 * @param distanceMeters  当前距离(m)
 * @param thresholdMeters 触发阈值(m)
 */
public record NearEncounter(
        String firstBodyId,
        String secondBodyId,
        double distanceMeters,
        double thresholdMeters) {
}