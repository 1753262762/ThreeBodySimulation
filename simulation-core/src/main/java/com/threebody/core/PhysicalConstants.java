package com.threebody.core;

/**
 * 物理常量与核心层固定限制。核心层始终使用 SI 单位。
 */
public final class PhysicalConstants {

    /** 万有引力常数(m^3 kg^-1 s^-2)。 */
    public static final double GRAVITATIONAL_CONSTANT = 6.67430e-11;

    /** 天文单位(m)，仅供预设与展示换算参考。 */
    public static final double ASTRONOMICAL_UNIT_METERS = 1.495978707e11;

    /** 太阳质量(kg)，仅供预设与展示换算参考。 */
    public static final double SOLAR_MASS_KG = 1.98892e30;

    /** 允许的最少天体数量。 */
    public static final int MIN_BODY_COUNT = 2;

    /** 允许的最多天体数量。 */
    public static final int MAX_BODY_COUNT = 100;

    /** 近距离事件阈值系数：距离小于 5 倍软化长度时记录事件。 */
    public static final double NEAR_ENCOUNTER_SOFTENING_FACTOR = 5.0;

    private PhysicalConstants() {
    }
}
