package com.threebody.core;

import java.util.List;

/**
 * 内置 A–C 预设。
 */
public final class Presets {

    /** 与旧实现等效的软化长度(m)。 */
    public static final double LEGACY_SOFTENING_LENGTH_METERS = 1.0e6;

    /** 旧实现的默认时间步长：12 小时(s)。 */
    public static final double LEGACY_TIME_STEP_SECONDS = 43200.0;

    /** 预设默认最大步数。 */
    public static final long DEFAULT_MAX_STEPS = 20_000L;

    /** 观赏型预设保留更多积分步，便于形成完整轨迹。 */
    public static final long SCENIC_MAX_STEPS = 200_000L;

    private Presets() {
    }

    public static List<Preset> all() {
        return List.of(presetA(), presetB(), presetC());
    }

    public static Preset byKey(PresetKey key) {
        return switch (key) {
            case A -> presetA();
            case B -> presetB();
            case C -> presetC();
        };
    }

    /** 方案 A：三颗恒星与一颗行星构成的稳定层级三星行星系统。 */
    public static Preset presetA() {
        List<BodySpec> bodies = List.of(
                body("A-1", "主恒星", "#ffd166", 1.9884700000001596e30,
                        Vector3.of(-531902686999.97253, -2991953669999.13, 0),
                        Vector3.of(1289.7328699999998, -6279.21418, 0)),
                body("A-2", "内层伴星", "#4d96ff", 1.5907760000005253e30,
                        Vector3.of(664880278000.14, -2991953669999.13, 0),
                        Vector3.of(1289.7328699999998, 7849.11927, 0)),
                body("A-3", "远伴星", "#ef476f", 1.1930820000008911e30,
                        Vector3.of(852845.0442374318, 8975875989995.133, 0),
                        Vector3.of(-3869.20508, 0.0451318, 0)),
                body("A-P1", "三体行星", "#76c893", 5.972199696199999e24,
                        Vector3.of(-681500558000.6642, -2991953669999.13, 0),
                        Vector3.of(1289.7328699999998, -36064.3563, 0)));
        return new Preset(PresetKey.A, "方案 A：稳定层级三星行星系统",
                "三颗恒星与一颗行星构成的层级引力系统，适合长期稳定性观测。",
                config("方案 A：稳定层级三星行星系统", bodies, 31557.6, 10_000_000L));
    }

    /** 方案 B：等质量双星与一颗轻质量外围行星。 */
    public static Preset presetB() {
        List<BodySpec> bodies = List.of(
                body("B-1", "蓝巨星", "#4dabf7", 1.0e30,
                        Vector3.of(-1.0e11, 0, 0),
                        Vector3.of(0, -12917, 0)),
                body("B-2", "红巨星", "#ff6b6b", 1.0e30,
                        Vector3.of(1.0e11, 0, 0),
                        Vector3.of(0, 12917, 0)),
                body("B-3", "薄荷行星", "#63e6be", 5.0e27,
                        Vector3.of(0, 4.5e11, 0),
                        Vector3.of(-17220, 0, 0)));
        return new Preset(PresetKey.B, "方案 B：双星花环",
                "一对互绕双星与外围轻质量行星组成的层次系统，可同时看到紧凑双环和宽阔外轨道。",
                scenicConfig("方案 B：双星花环", bodies, 9467.28));
    }

    /** 方案 C：等质量天体位于等边三角形顶点并绕质心旋转。 */
    public static Preset presetC() {
        List<BodySpec> bodies = List.of(
                body("F-1", "翡翠星", "#38d9a9", 1.0e30,
                        Vector3.of(1.73205080760e11, 0, 0),
                        Vector3.of(0, 14920, 0)),
                body("C-2", "紫晶星", "#b197fc", 1.0e30,
                        Vector3.of(-8.6602540380e10, 1.5e11, 0),
                        Vector3.of(-12918, -7460, 0)),
                body("C-3", "琥珀星", "#ffa94d", 1.0e30,
                        Vector3.of(-8.6602540380e10, -1.5e11, 0),
                        Vector3.of(12918, -7460, 0)));
        return new Preset(PresetKey.C, "方案 C：三角华尔兹",
                "拉格朗日等边三角构型，三颗等质量天体保持近似三角形队形绕质心旋转，轨迹呈三重环带。",
                new SimulationConfig(
                        "方案 C：三角华尔兹",
                        bodies,
                        108,
                        PhysicalConstants.GRAVITATIONAL_CONSTANT,
                        1.0e7,
                        20_000_000L,
                        null));
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

    private static SimulationConfig config(String name, List<BodySpec> bodies, double timeStepSeconds,
            long maxSteps) {
        return new SimulationConfig(
                name,
                bodies,
                timeStepSeconds,
                PhysicalConstants.GRAVITATIONAL_CONSTANT,
                LEGACY_SOFTENING_LENGTH_METERS,
                maxSteps,
                null);
    }

    private static SimulationConfig scenicConfig(String name, List<BodySpec> bodies, double timeStepSeconds) {
        return new SimulationConfig(
                name,
                bodies,
                timeStepSeconds,
                PhysicalConstants.GRAVITATIONAL_CONSTANT,
                1.0e7,
                SCENIC_MAX_STEPS,
                null);
    }
}
