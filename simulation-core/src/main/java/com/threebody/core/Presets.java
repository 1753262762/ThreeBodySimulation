package com.threebody.core;

import java.util.List;

/**
 * 内置 A-D 预设，数值与旧 Swing 界面的四种初始方案一一对应，用于回归对比。
 *
 * <p>旧实现通过跳过距离小于 1e6 m 的天体对来避免奇点，本实现改用软化长度
 * {@value #LEGACY_SOFTENING_LENGTH_METERS} m 达到同样目的；在预设的 1e11 m 量级
 * 特征距离下，两者的加速度差异约为 1e-22 相对量级，可忽略。
 */
public final class Presets {

    /** 与旧实现等效的软化长度(m)。 */
    public static final double LEGACY_SOFTENING_LENGTH_METERS = 1.0e6;

    /** 旧实现的默认时间步长：12 小时(s)。 */
    public static final double LEGACY_TIME_STEP_SECONDS = 43200.0;

    /** 预设默认最大步数。 */
    public static final long DEFAULT_MAX_STEPS = 20_000L;

    private Presets() {
    }

    public static List<Preset> all() {
        return List.of(presetA(), presetB(), presetC(), presetD());
    }

    public static Preset byKey(PresetKey key) {
        return switch (key) {
            case A -> presetA();
            case B -> presetB();
            case C -> presetC();
            case D -> presetD();
        };
    }

    /** 方案 A：三个质量相近天体构成的典型混沌三体系统。 */
    public static Preset presetA() {
        List<BodySpec> bodies = List.of(
                body("A-1", "恒星甲", "#ffd166", 10.989e30,
                        Vector3.of(2.1e8, 1.2e6, 0), Vector3.of(1919, 1145, 0)),
                body("A-2", "恒星乙", "#4d96ff", 10.289e30,
                        Vector3.of(2.796e11, 2.796e11, 0), Vector3.of(20000, -20000, 0)),
                body("A-3", "恒星丙", "#ef476f", 10.289e30,
                        Vector3.of(-2.796e11, -2.796e11, 0), Vector3.of(-20000, 20000, 0)));
        return new Preset(PresetKey.A, "方案 A：混沌三体",
                "三个质量相近的天体，初始略微不对称，长期演化表现出典型的混沌轨道。",
                config("方案 A：混沌三体", bodies, LEGACY_TIME_STEP_SECONDS));
    }

    /** 方案 B：水平对称的四体配置，时间步长为默认值的四分之一。 */
    public static Preset presetB() {
        List<BodySpec> bodies = List.of(
                body("B-1", "中心恒星", "#ffd166", 10.989e30, Vector3.ZERO, Vector3.ZERO),
                body("B-2", "伴星东", "#4d96ff", 10.289e30,
                        Vector3.of(2.796e11, 0, 0), Vector3.of(0, 20000, 0)),
                body("B-3", "伴星西", "#ef476f", 10.289e30,
                        Vector3.of(-2.796e11, 0, 0), Vector3.of(0, -20000, 0)),
                body("B-4", "外围天体", "#06d6a0", 5.289e30,
                        Vector3.of(-4.796e11, 8, 9), Vector3.of(0, 0, 20000)));
        return new Preset(PresetKey.B, "方案 B：水平对称四体",
                "中心恒星与两颗对称伴星，外加一颗带 Z 向速度的外围天体，用于观察三维演化。",
                config("方案 B：水平对称四体", bodies, LEGACY_TIME_STEP_SECONDS / 4.0));
    }

    /** 方案 C：对角对称配置，第三个天体质量更大。 */
    public static Preset presetC() {
        List<BodySpec> bodies = List.of(
                body("C-1", "中心恒星", "#ffd166", 10.989e30, Vector3.ZERO, Vector3.ZERO),
                body("C-2", "伴星甲", "#4d96ff", 10.289e30,
                        Vector3.of(2.796e11, 2.796e11, 0), Vector3.of(20000, -20000, 0)),
                body("C-3", "重伴星", "#ef476f", 13.289e30,
                        Vector3.of(-2.796e11, -2.796e11, 0), Vector3.of(-20000, 20000, 0)));
        return new Preset(PresetKey.C, "方案 C：对角对称三体",
                "对角分布的三体系统，第三个天体质量明显更大，质心存在偏移。",
                config("方案 C：对角对称三体", bodies, LEGACY_TIME_STEP_SECONDS));
    }

    /** 方案 D：与方案 C 同构但质量差异更小。 */
    public static Preset presetD() {
        List<BodySpec> bodies = List.of(
                body("D-1", "中心恒星", "#ffd166", 10.989e30, Vector3.ZERO, Vector3.ZERO),
                body("D-2", "伴星甲", "#4d96ff", 10.289e30,
                        Vector3.of(2.796e11, 2.796e11, 0), Vector3.of(20000, -20000, 0)),
                body("D-3", "伴星乙", "#ef476f", 12.289e30,
                        Vector3.of(-2.796e11, -2.796e11, 0), Vector3.of(-20000, 20000, 0)));
        return new Preset(PresetKey.D, "方案 D：紧凑三体",
                "与方案 C 同构但质量差异更小，运动更为剧烈，适合对比质量比的影响。",
                config("方案 D：紧凑三体", bodies, LEGACY_TIME_STEP_SECONDS));
    }

    private static BodySpec body(String id, String name, String color, double massKg,
            Vector3 position, Vector3 velocity) {
        return new BodySpec(id, name, color, massKg, position, velocity);
    }

    private static SimulationConfig config(String name, List<BodySpec> bodies, double timeStepSeconds) {
        return new SimulationConfig(
                name,
                bodies,
                timeStepSeconds,
                PhysicalConstants.GRAVITATIONAL_CONSTANT,
                LEGACY_SOFTENING_LENGTH_METERS,
                DEFAULT_MAX_STEPS,
                null);
    }
}