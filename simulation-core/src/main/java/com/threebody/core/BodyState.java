package com.threebody.core;

/**
 * 单个天体在某一步的瞬时状态。
 *
 * @param id       天体标识
 * @param position 位置(m)
 * @param velocity 速度(m/s)
 */
public record BodyState(String id, Vector3 position, Vector3 velocity) {
}