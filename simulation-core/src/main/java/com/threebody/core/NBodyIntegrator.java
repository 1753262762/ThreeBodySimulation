package com.threebody.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 四阶龙格-库塔(RK4)N 体积分器，使用软化引力：
 * a_i = sum_j G * m_j * r_ij / (|r_ij|^2 + eps^2)^(3/2)
 *
 * <p>软化长度 eps 消除近距离奇点，因此不需要距离截断，也不会因两体极近而产生无穷加速度。
 * 本类无状态且线程安全，所有输入输出均为 SI 单位。
 */
public final class NBodyIntegrator {

    private NBodyIntegrator() {
    }

    /**
     * 由配置构造初始状态(step = 0)。
     */
    public static SimulationState initialState(SimulationConfig config) {
        List<BodyState> states = new ArrayList<>(config.bodyCount());
        for (BodySpec spec : config.bodies()) {
            states.add(new BodyState(spec.id(), spec.position(), spec.velocity()));
        }
        return new SimulationState(0L, 0.0, states);
    }

    /**
     * 推进一步。
     *
     * @param config 模拟配置，提供步长、引力常数、软化长度与质量
     * @param state  当前状态
     * @return 推进后的状态与本步近距离事件
     * @throws NumericalInstabilityException 结果包含非有限值时抛出
     */
    public static StepResult step(SimulationConfig config, SimulationState state) {
        double dt = config.timeStepSeconds();
        double[] masses = massArray(config);
        int n = masses.length;

        double[][] pos = new double[n][3];
        double[][] vel = new double[n][3];
        fill(state, pos, vel);

        // RK4：位置导数为速度，速度导数为加速度
        long nextStep = state.step() + 1;
        double[][] a1 = accelerations(config, masses, pos, nextStep, state.simulationTimeSeconds());

        double[][] pos2 = advance(pos, vel, dt / 2.0);
        double[][] vel2 = advance(vel, a1, dt / 2.0);
        double[][] a2 = accelerations(config, masses, pos2, nextStep, state.simulationTimeSeconds() + dt / 2.0);

        double[][] pos3 = advance(pos, vel2, dt / 2.0);
        double[][] vel3 = advance(vel, a2, dt / 2.0);
        double[][] a3 = accelerations(config, masses, pos3, nextStep, state.simulationTimeSeconds() + dt / 2.0);

        double[][] pos4 = advance(pos, vel3, dt);
        double[][] vel4 = advance(vel, a3, dt);
        double[][] a4 = accelerations(config, masses, pos4, nextStep, state.simulationTimeSeconds() + dt);

        double[][] newPos = new double[n][3];
        double[][] newVel = new double[n][3];
        double sixth = dt / 6.0;
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < 3; c++) {
                newPos[i][c] = pos[i][c] + sixth * (vel[i][c] + 2 * vel2[i][c] + 2 * vel3[i][c] + vel4[i][c]);
                newVel[i][c] = vel[i][c] + sixth * (a1[i][c] + 2 * a2[i][c] + 2 * a3[i][c] + a4[i][c]);
            }
        }

        List<BodyState> nextBodies = new ArrayList<>(n);
        List<BodySpec> specs = config.bodies();
        for (int i = 0; i < n; i++) {
            Vector3 p = new Vector3(newPos[i][0], newPos[i][1], newPos[i][2]);
            Vector3 v = new Vector3(newVel[i][0], newVel[i][1], newVel[i][2]);
            if (!p.isFinite() || !v.isFinite()) {
                NonFiniteComponent component = firstNonFinite(p, v);
                throw new NumericalInstabilityException(
                        "天体 " + specs.get(i).name() + " 在第 " + nextStep + " 步出现非有限数值，请减小时间步长或增大软化长度",
                        nextStep, specs.get(i).id(), component.field(),
                        state.simulationTimeSeconds() + dt, component.value());
            }
            nextBodies.add(new BodyState(specs.get(i).id(), p, v));
        }

        double nextTime = state.simulationTimeSeconds() + dt;
        SimulationState next = new SimulationState(nextStep, nextTime, nextBodies);
        return new StepResult(next, detectNearEncounters(config, next));
    }

    /**
     * 检测近距离事件；每对天体最多产生一条记录。
     */
    public static List<NearEncounter> detectNearEncounters(SimulationConfig config, SimulationState state) {
        double threshold = config.nearEncounterThresholdMeters();
        if (threshold <= 0.0) {
            return List.of();
        }
        List<BodyState> bodies = state.bodies();
        List<NearEncounter> found = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            for (int j = i + 1; j < bodies.size(); j++) {
                double distance = bodies.get(j).position().subtract(bodies.get(i).position()).length();
                if (distance < threshold) {
                    found.add(new NearEncounter(bodies.get(i).id(), bodies.get(j).id(), distance, threshold));
                }
            }
        }
        return found;
    }

    /**
     * 计算软化引力加速度(m/s^2)。
     */
    static double[][] accelerations(SimulationConfig config, double[] masses, double[][] pos,
            long step, double simulationTimeSeconds) {
        int n = masses.length;
        double g = config.gravitationalConstant();
        double eps2 = config.softeningLengthMeters() * config.softeningLengthMeters();
        double[][] acc = new double[n][3];

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dx = pos[j][0] - pos[i][0];
                double dy = pos[j][1] - pos[i][1];
                double dz = pos[j][2] - pos[i][2];
                double r2 = dx * dx + dy * dy + dz * dz + eps2;
                // 防御性检查：当软化长度为 0 且两天体位置完全相同时，
                // 或 r2 下溢至 0 时跳过以避免除零
                if (!Double.isFinite(r2) || r2 <= 1e-300) {
                    throw new NumericalInstabilityException(
                            "天体间距过小，无法计算有限引力加速度；请增大软化长度",
                            step, config.bodies().get(i).id() + "," + config.bodies().get(j).id(),
                            "pairDistanceSquared", simulationTimeSeconds,
                            Double.isFinite(r2) ? Double.toString(r2) : finiteValueText(r2));
                }
                double invR3 = 1.0 / (r2 * Math.sqrt(r2));
                double factorI = g * masses[j] * invR3;
                double factorJ = g * masses[i] * invR3;
                acc[i][0] += factorI * dx;
                acc[i][1] += factorI * dy;
                acc[i][2] += factorI * dz;
                acc[j][0] -= factorJ * dx;
                acc[j][1] -= factorJ * dy;
                acc[j][2] -= factorJ * dz;
            }
        }
        return acc;
    }

    static double[] massArray(SimulationConfig config) {
        List<BodySpec> specs = config.bodies();
        double[] masses = new double[specs.size()];
        for (int i = 0; i < masses.length; i++) {
            masses[i] = specs.get(i).massKg();
        }
        return masses;
    }

    private static void fill(SimulationState state, double[][] pos, double[][] vel) {
        List<BodyState> bodies = state.bodies();
        for (int i = 0; i < bodies.size(); i++) {
            BodyState b = bodies.get(i);
            pos[i][0] = b.position().x();
            pos[i][1] = b.position().y();
            pos[i][2] = b.position().z();
            vel[i][0] = b.velocity().x();
            vel[i][1] = b.velocity().y();
            vel[i][2] = b.velocity().z();
        }
    }

    private static double[][] advance(double[][] base, double[][] derivative, double h) {
        int n = base.length;
        double[][] out = new double[n][3];
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < 3; c++) {
                out[i][c] = base[i][c] + h * derivative[i][c];
            }
        }
        return out;
    }

    private static NonFiniteComponent firstNonFinite(Vector3 position, Vector3 velocity) {
        double[] values = {position.x(), position.y(), position.z(), velocity.x(), velocity.y(), velocity.z()};
        String[] fields = {"position.x", "position.y", "position.z", "velocity.x", "velocity.y", "velocity.z"};
        for (int i = 0; i < values.length; i++) {
            if (!Double.isFinite(values[i])) {
                return new NonFiniteComponent(fields[i], finiteValueText(values[i]));
            }
        }
        return new NonFiniteComponent("state", "NaN");
    }

    private static String finiteValueText(double value) {
        if (Double.isNaN(value)) return "NaN";
        return value > 0.0 ? "Infinity" : "-Infinity";
    }

    private record NonFiniteComponent(String field, String value) {}
}
