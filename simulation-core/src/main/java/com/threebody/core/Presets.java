package com.threebody.core;

import java.util.List;

/**
 * 内置 A–J 预设。
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
        return List.of(
                presetA(), presetB(), presetC(), presetD(), presetE(),
                presetF(), presetG(), presetH(), presetI(), presetJ());
    }

    public static Preset byKey(PresetKey key) {
        return switch (key) {
            case A -> presetA();
            case B -> presetB();
            case C -> presetC();
            case D -> presetD();
            case E -> presetE();
            case F -> presetF();
            case G -> presetG();
            case H -> presetH();
            case I -> presetI();
            case J -> presetJ();
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

    /** 方案 D：由十组层级双星构成的二十天体压力测试。 */
    public static Preset presetD() {
        List<BodySpec> bodies = List.of(
                body("D-01", "双星01-甲", "#ffd166", 1.98847e30,
                        Vector3.of(299494937141400.0, 0.0, 0.0),
                        Vector3.of(0.0, 12381.743229, 0.0)),
                body("D-02", "双星01-乙", "#4d96ff", 1.98847e30,
                        Vector3.of(298896545658600.0, 0.0, 0.0),
                        Vector3.of(0.0, -8679.532777, 0.0)),
                body("D-03", "双星02-甲", "#ef476f", 1.98847e30,
                        Vector3.of(242296493876649.25, 176038707187976.2, 0.0),
                        Vector3.of(-7277.806068, 10017.040692, 0.0)),
                body("D-04", "双星02-乙", "#76c893", 1.98847e30,
                        Vector3.of(241812384997774.8, 175686981499288.94, 0.0),
                        Vector3.of(5101.701363, -7021.88952, 0.0)),
                body("D-05", "双星03-甲", "#9b5de5", 1.98847e30,
                        Vector3.of(92549025305949.25, 284836611565735.9, 0.0),
                        Vector3.of(-11775.737581, 3826.169078, 0.0)),
                body("D-06", "双星03-乙", "#f15bb5", 1.98847e30,
                        Vector3.of(92364112168474.81, 284267507446723.4, 0.0),
                        Vector3.of(8254.726206, -2682.123131, 0.0)),
                body("D-07", "双星04-甲", "#00bbf9", 1.98847e30,
                        Vector3.of(-92549025305949.22, 284836611565735.94, 0.0),
                        Vector3.of(-11775.737581, -3826.169078, 0.0)),
                body("D-08", "双星04-乙", "#00f5d4", 1.98847e30,
                        Vector3.of(-92364112168474.78, 284267507446723.44, 0.0),
                        Vector3.of(8254.726206, 2682.123131, 0.0)),
                body("D-09", "双星05-甲", "#f8961e", 1.98847e30,
                        Vector3.of(-242296493876649.22, 176038707187976.22, 0.0),
                        Vector3.of(-7277.806068, -10017.040692, 0.0)),
                body("D-10", "双星05-乙", "#90be6d", 1.98847e30,
                        Vector3.of(-241812384997774.78, 175686981499288.97, 0.0),
                        Vector3.of(5101.701363, 7021.88952, 0.0)),
                body("D-11", "双星06-甲", "#f9844a", 1.98847e30,
                        Vector3.of(-299494937141400.0, 0.0, 0.0),
                        Vector3.of(0.0, -12381.743229, 0.0)),
                body("D-12", "双星06-乙", "#577590", 1.98847e30,
                        Vector3.of(-298896545658600.0, 0.0, 0.0),
                        Vector3.of(0.0, 8679.532777, 0.0)),
                body("D-13", "双星07-甲", "#43aa8b", 1.98847e30,
                        Vector3.of(-242296493876649.28, -176038707187976.16, 0.0),
                        Vector3.of(7277.806068, -10017.040692, 0.0)),
                body("D-14", "双星07-乙", "#277da1", 1.98847e30,
                        Vector3.of(-241812384997774.84, -175686981499288.9, 0.0),
                        Vector3.of(-5101.701363, 7021.88952, 0.0)),
                body("D-15", "双星08-甲", "#f94144", 1.98847e30,
                        Vector3.of(-92549025305949.28, -284836611565735.9, 0.0),
                        Vector3.of(11775.737581, -3826.169078, 0.0)),
                body("D-16", "双星08-乙", "#f3722c", 1.98847e30,
                        Vector3.of(-92364112168474.84, -284267507446723.4, 0.0),
                        Vector3.of(-8254.726206, 2682.123131, 0.0)),
                body("D-17", "双星09-甲", "#f9c74f", 1.98847e30,
                        Vector3.of(92549025305949.19, -284836611565735.94, 0.0),
                        Vector3.of(11775.737581, 3826.169078, 0.0)),
                body("D-18", "双星09-乙", "#90be6d", 1.98847e30,
                        Vector3.of(92364112168474.75, -284267507446723.44, 0.0),
                        Vector3.of(-8254.726206, -2682.123131, 0.0)),
                body("D-19", "双星10-甲", "#8338ec", 1.98847e30,
                        Vector3.of(242296493876649.22, -176038707187976.25, 0.0),
                        Vector3.of(7277.806068, 10017.040692, 0.0)),
                body("D-20", "双星10-乙", "#3a86ff", 1.98847e30,
                        Vector3.of(241812384997774.78, -175686981499289.0, 0.0),
                        Vector3.of(-5101.701363, -7021.88952, 0.0)));
        return new Preset(PresetKey.D, "方案 D：20 星层级双星压力测试",
                "十组层级双星构成的 20 天体压力测试，用于观察密集场景性能与多尺度相互作用。",
                importedConfig("方案 D：20 星层级双星压力测试", bodies, 21600.0, 1.0e6, 500_000L));
    }

    /** 方案 E：基于 J2000 状态量的太阳与八大行星三维系统。 */
    public static Preset presetE() {
        List<BodySpec> bodies = List.of(
                body("SOL", "太阳", "#ffd166", 1.988409871316534e30,
                        Vector3.of(-709543679.393, -745870359.754, 27508776.9794),
                        Vector3.of(6.63117489155, -12.3329337784, -0.143732414619)),
                body("MERCURY", "水星", "#a9a9a9", 3.301000636920726e23,
                        Vector3.of(9404824467.53, 44046268369.6, 2758201652.26),
                        Vector3.of(-57275.3728432, 12539.2974997, 6282.84229399)),
                body("VENUS", "金星", "#e9c46a", 4.867305814842006e24,
                        Vector3.of(-71919068922.1, 79585973872.0, 5235701244.14),
                        Vector3.of(-26324.1526908, -23430.8355541, 1199.67488694)),
                body("EARTH", "地球(地月质心简化)", "#4d96ff", 6.045626292270351e24,
                        Vector3.of(-33643547936.7, 142618205836.0, 27470468.6777),
                        Vector3.of(-29511.1378997, -6793.23898892, -0.141920489332)),
                body("MARS", "火星", "#ef476f", 6.41690901158174e23,
                        Vector3.of(188083414936.0, -84547366264.1, -6368243809.57),
                        Vector3.of(10763.6969909, 24203.2838727, 242.7313287)),
                body("JUPITER", "木星", "#d4a373", 1.8985176587806962e27,
                        Vector3.of(715444440571.0, 187528818929.0, -16786903618.6),
                        Vector3.of(-3477.62915213, 13243.0659681, 23.0027375724)),
                body("SATURN", "土星", "#f4d35e", 5.684578883448452e26,
                        Vector3.of(-62333369003.3, 1347506140050.0, -21015105769.6),
                        Vector3.of(-10153.8052026, -470.299997983, 411.874532177)),
                body("URANUS", "天王星", "#90e0ef", 8.68189383156286e25,
                        Vector3.of(-2701455787260.0, 429189740978.0, 36638035173.7),
                        Vector3.of(-1114.22066579, -7052.25065493, -11.7543035026)),
                body("NEPTUNE", "海王星", "#4361ee", 1.0243062344485564e26,
                        Vector3.of(3153166623580.0, 3149419932650.0, -137513927028.0),
                        Vector3.of(-3864.72788716, 3863.99586206, 9.24495222065)));
        return new Preset(PresetKey.E, "方案 E：真实太阳系 J2000 三维版",
                "基于 J2000 状态量的太阳与八大行星三维系统，适合长期太阳系轨道观察。",
                importedConfig("方案 E：真实太阳系 J2000 三维版", bodies, 21600.0, 1.0e6, 500_000L));
    }

    /** 方案 F：地球与月球围绕共同质心运行的稳定双体系统。 */
    public static Preset presetF() {
        List<BodySpec> bodies = List.of(
                body("EM-1", "地球", "#4d96ff", 5.972168398184079e24,
                        Vector3.of(-4670672.616916974, 0.0, 0.0),
                        Vector3.of(0.0, -12.448858782099563, 0.0)),
                body("EM-2", "月球", "#d9d9d9", 7.34578917639303e22,
                        Vector3.of(379728341.383083, 0.0, 0.0),
                        Vector3.of(0.0, 1012.0993024253586, 0.0)));
        return new Preset(PresetKey.F, "方案 F：地月系统稳定双体轨道",
                "地球与月球围绕共同质心运行的稳定双体系统，适合基础轨道验证。",
                importedConfig("方案 F：地月系统稳定双体轨道", bodies, 1800.0, 1000.0, 200_000L));
    }

    /** 方案 G：分别位于 XY 与 XZ 平面的双轨道三体系统。 */
    public static Preset presetG() {
        List<BodySpec> bodies = List.of(
                body("ORTHO-0", "中央恒星", "#ffd166", 1.989e30,
                        Vector3.of(0.0, 0.0, 0.0),
                        Vector3.of(-54.61465682052287, -59.82735902200749, 0.0)),
                body("ORTHO-1", "XY轨道天体", "#4d96ff", 4.0e27,
                        Vector3.of(1.5e11, 0.0, 0.0),
                        Vector3.of(0.0, 29749.154273693224, 0.0)),
                body("ORTHO-2", "XZ轨道天体", "#ff6b6b", 4.0e27,
                        Vector3.of(0.0, 0.0, 1.8e11),
                        Vector3.of(27157.138104004993, 0.0, 0.0)));
        return new Preset(PresetKey.G, "方案 G：正交双轨道系统",
                "中央恒星与分别位于 XY、XZ 平面的两颗轨道天体组成的三维展示系统。",
                importedConfig("3D展示:正交双轨道系统", bodies, 1800.0, 1.0e6,
                        200_000L, 63_072_000.0));
    }

    /** 方案 H：轨道伴星与高速穿越天体构成的三维系统。 */
    public static Preset presetH() {
        List<BodySpec> bodies = List.of(
                body("HELIX-0", "中央恒星", "#ffd166", 1.989e30,
                        Vector3.of(0.0, 0.0, 0.0),
                        Vector3.of(0.0, -37.0, 0.0)),
                body("HELIX-1", "轨道伴星", "#4d96ff", 2.5e27,
                        Vector3.of(1.5e11, 0.0, 0.0),
                        Vector3.of(0.0, 29749.154273693224, 0.0)),
                body("HELIX-2", "穿越天体", "#c77dff", 5.0e25,
                        Vector3.of(7.0e10, -4.0e10, -3.0e11),
                        Vector3.of(-9000.0, 13000.0, 24000.0)));
        return new Preset(PresetKey.H, "方案 H：螺旋穿越系统",
                "轨道伴星与高速穿越天体构成的三维系统，用于观察空间轨迹与近距离掠过。",
                importedConfig("3D展示:螺旋穿越系统", bodies, 900.0, 1.0e6,
                        200_000L, 31_557_600.0));
    }

    /** 方案 I：三颗大质量天体处于非共面初始状态。 */
    public static Preset presetI() {
        List<BodySpec> bodies = List.of(
                body("CHAOS-1", "赤星", "#ff595e", 7.0e29,
                        Vector3.of(-8.0e10, -2.0e10, 5.0e10),
                        Vector3.of(7000.0, 15000.0, -9000.0)),
                body("CHAOS-2", "蓝星", "#4d96ff", 8.0e29,
                        Vector3.of(8.0e10, 3.0e10, -4.0e10),
                        Vector3.of(-11000.0, -12000.0, 10000.0)),
                body("CHAOS-3", "金星", "#ffd166", 6.0e29,
                        Vector3.of(1.0e10, 1.1e11, 8.0e10),
                        Vector3.of(-14000.0, -6000.0, -8000.0)));
        return new Preset(PresetKey.I, "方案 I：三维混沌近遇",
                "三颗大质量天体处于非共面初始状态，用于观察三维混沌运动与近遇。",
                importedConfig("3D展示:三维混沌近遇", bodies, 1200.0, 1.0e8,
                        200_000L, 31_557_600.0));
    }

    /** 方案 J：三颗轨道天体分别位于 XY、XZ 与 YZ 平面。 */
    public static Preset presetJ() {
        List<BodySpec> bodies = List.of(
                body("ORTHO3-0", "中央恒星", "#ffd166", 1.989e30,
                        Vector3.of(0.0, 0.0, 0.0),
                        Vector3.of(-50.563347026620136, -59.82735902200749, -54.61465682052287)),
                body("ORTHO3-XY", "XY轨道", "#4d96ff", 4.0e27,
                        Vector3.of(1.5e11, 0.0, 0.0),
                        Vector3.of(0.0, 29749.154273693224, 0.0)),
                body("ORTHO3-XZ", "XZ轨道", "#ff6b6b", 4.0e27,
                        Vector3.of(0.0, 0.0, 1.8e11),
                        Vector3.of(27157.138104004993, 0.0, 0.0)),
                body("ORTHO3-YZ", "YZ轨道", "#80ed99", 4.0e27,
                        Vector3.of(0.0, 2.1e11, 0.0),
                        Vector3.of(0.0, 0.0, 25142.624308986862)));
        return new Preset(PresetKey.J, "方案 J：四体三平面正交轨道",
                "中央恒星与分别位于 XY、XZ、YZ 三个平面的轨道天体组成的四体三维系统。",
                importedConfig("3D展示:四体三平面正交轨道", bodies, 1800.0, 1.0e6,
                        200_000L, 94_608_000.0));
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

    private static SimulationConfig importedConfig(String name, List<BodySpec> bodies, double timeStepSeconds,
            double softeningLengthMeters, long maxSteps) {
        return importedConfig(name, bodies, timeStepSeconds, softeningLengthMeters, maxSteps, null);
    }

    private static SimulationConfig importedConfig(String name, List<BodySpec> bodies, double timeStepSeconds,
            double softeningLengthMeters, long maxSteps, Double targetSimulationTimeSeconds) {
        return new SimulationConfig(
                name,
                bodies,
                timeStepSeconds,
                PhysicalConstants.GRAVITATIONAL_CONSTANT,
                softeningLengthMeters,
                maxSteps,
                targetSimulationTimeSeconds);
    }
}
