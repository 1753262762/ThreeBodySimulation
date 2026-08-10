package com.threebody.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NBodyIntegratorTest {

    private static SimulationConfig twoBodyCircularOrbit() {
        // 中心大质量天体加一个近圆轨道小天体，便于检验能量与角动量守恒
        double centralMass = 1.98892e30;
        double radius = 1.495978707e11;
        double orbitalSpeed = Math.sqrt(PhysicalConstants.GRAVITATIONAL_CONSTANT * centralMass / radius);
        return new SimulationConfig(
                "双体圆轨道",
                List.of(
                        new BodySpec("sun", "中心天体", "#ffd166", centralMass, Vector3.ZERO, Vector3.ZERO),
                        new BodySpec("planet", "环绕天体", "#4d96ff", 5.972e24,
                                Vector3.of(radius, 0, 0), Vector3.of(0, orbitalSpeed, 0))),
                3600.0,
                PhysicalConstants.GRAVITATIONAL_CONSTANT,
                1.0e6,
                8760L,
                null);
    }

    @Test
    @DisplayName("初始状态步数为零且保留初始位置速度")
    void initialState() {
        SimulationConfig config = twoBodyCircularOrbit();
        SimulationState state = NBodyIntegrator.initialState(config);
        assertEquals(0L, state.step());
        assertEquals(0.0, state.simulationTimeSeconds());
        assertEquals(2, state.bodies().size());
        assertEquals(config.bodies().get(0).position(), state.bodies().get(0).position());
        assertEquals(config.bodies().get(1).velocity(), state.bodies().get(1).velocity());
    }

    @Test
    @DisplayName("RK4 结果完全确定：相同输入产生逐位相同的输出")
    void deterministic() {
        SimulationConfig config = twoBodyCircularOrbit();
        SimulationState first = NBodyIntegrator.initialState(config);
        SimulationState second = NBodyIntegrator.initialState(config);
        for (int i = 0; i < 200; i++) {
            first = NBodyIntegrator.step(config, first).state();
            second = NBodyIntegrator.step(config, second).state();
        }
        assertEquals(first.bodies().get(1).position(), second.bodies().get(1).position());
        assertEquals(first.bodies().get(1).velocity(), second.bodies().get(1).velocity());
    }

    @Test
    @DisplayName("一年圆轨道后能量与角动量的相对漂移极小")
    void conservesEnergyAndAngularMomentum() {
        SimulationConfig config = twoBodyCircularOrbit();
        SimulationState state = NBodyIntegrator.initialState(config);
        double initialEnergy = MetricsCalculator.totalEnergy(config, state);
        double initialAngular = MetricsCalculator.angularMomentum(config, state).length();

        for (int i = 0; i < 8760; i++) {
            state = NBodyIntegrator.step(config, state).state();
        }

        double energyDrift = Math.abs(MetricsCalculator.relativeEnergyDrift(initialEnergy,
                MetricsCalculator.totalEnergy(config, state)));
        double angularDrift = Math.abs(
                (MetricsCalculator.angularMomentum(config, state).length() - initialAngular) / initialAngular);
        assertTrue(energyDrift < 1e-9, "能量相对漂移应小于 1e-9，实际为 " + energyDrift);
        assertTrue(angularDrift < 1e-9, "角动量相对漂移应小于 1e-9，实际为 " + angularDrift);
    }

    @Test
    @DisplayName("圆轨道运行一年后回到初始位置附近")
    void closesCircularOrbit() {
        SimulationConfig config = twoBodyCircularOrbit();
        double radius = config.bodies().get(1).position().x();
        double orbitalPeriod = 2 * Math.PI * Math.sqrt(Math.pow(radius, 3)
                / (PhysicalConstants.GRAVITATIONAL_CONSTANT * config.bodies().get(0).massKg()));
        long steps = Math.round(orbitalPeriod / config.timeStepSeconds());

        SimulationState state = NBodyIntegrator.initialState(config);
        for (long i = 0; i < steps; i++) {
            state = NBodyIntegrator.step(config, state).state();
        }
        double distanceFromStart = state.bodies().get(1).position()
                .subtract(config.bodies().get(1).position()).length();
        assertTrue(distanceFromStart < radius * 0.01,
                "一个周期后应回到起点 1% 半径内，实际偏离 " + distanceFromStart + " m");
    }

    @Test
    @DisplayName("孤立两体系统的总动量守恒")
    void conservesLinearMomentum() {
        SimulationConfig config = twoBodyCircularOrbit();
        SimulationState state = NBodyIntegrator.initialState(config);
        Vector3 initial = MetricsCalculator.linearMomentum(config, state);
        for (int i = 0; i < 500; i++) {
            state = NBodyIntegrator.step(config, state).state();
        }
        Vector3 current = MetricsCalculator.linearMomentum(config, state);
        double scale = Math.max(initial.length(), 1.0);
        assertTrue(current.subtract(initial).length() / scale < 1e-10,
                "动量漂移过大：" + current.subtract(initial).length());
    }

    @Test
    @DisplayName("软化引力使极近距离两体保持有限加速度")
    void softeningPreventsSingularity() {
        SimulationConfig config = new SimulationConfig(
                "极近两体",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 1.0e30, Vector3.ZERO, Vector3.ZERO),
                        new BodySpec("b", "乙", "#4d96ff", 1.0e30, Vector3.of(1.0, 0, 0), Vector3.ZERO)),
                1.0,
                PhysicalConstants.GRAVITATIONAL_CONSTANT,
                1.0e6,
                10L,
                null);
        SimulationState state = NBodyIntegrator.initialState(config);
        for (int i = 0; i < 10; i++) {
            state = NBodyIntegrator.step(config, state).state();
            for (BodyState body : state.bodies()) {
                assertTrue(body.position().isFinite() && body.velocity().isFinite(),
                        "软化引力下不应出现非有限值");
            }
        }
    }

    @Test
    @DisplayName("软化长度为零且天体重合时抛出数值不稳定异常")
    void reportsNumericalInstability() {
        SimulationConfig config = new SimulationConfig(
                "重合两体",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 1.0e30, Vector3.ZERO, Vector3.ZERO),
                        new BodySpec("b", "乙", "#4d96ff", 1.0e30, Vector3.of(1e-160, 0, 0), Vector3.ZERO)),
                1.0e6,
                PhysicalConstants.GRAVITATIONAL_CONSTANT,
                0.0,
                10L,
                null);
        SimulationState state = NBodyIntegrator.initialState(config);
        assertThrows(NumericalInstabilityException.class, () -> {
            SimulationState current = state;
            for (int i = 0; i < 50; i++) {
                current = NBodyIntegrator.step(config, current).state();
            }
        });
    }

    @Test
    @DisplayName("距离小于 5 倍软化长度时记录近距离事件但不中止模拟")
    void detectsNearEncounter() {
        double softening = 1.0e9;
        SimulationConfig config = new SimulationConfig(
                "近距离配置",
                List.of(
                        new BodySpec("a", "甲", "#ffd166", 1.0e30, Vector3.ZERO, Vector3.ZERO),
                        new BodySpec("b", "乙", "#4d96ff", 1.0e30, Vector3.of(2.0e9, 0, 0), Vector3.ZERO)),
                1.0,
                PhysicalConstants.GRAVITATIONAL_CONSTANT,
                softening,
                5L,
                null);
        SimulationState state = NBodyIntegrator.initialState(config);
        StepResult result = NBodyIntegrator.step(config, state);
        assertTrue(result.hasNearEncounter());
        NearEncounter encounter = result.nearEncounters().get(0);
        assertEquals(5.0 * softening, encounter.thresholdMeters());
        assertEquals(2, result.state().bodies().size(), "近距离事件不得合并天体");
        assertTrue(encounter.distanceMeters() < encounter.thresholdMeters());
    }

    @Test
    @DisplayName("20 体系统可以稳定推进且状态保持有限")
    void supportsTwentyBodies() {
        List<BodySpec> bodies = new java.util.ArrayList<>();
        for (int i = 0; i < PhysicalConstants.MAX_BODY_COUNT; i++) {
            double angle = 2 * Math.PI * i / PhysicalConstants.MAX_BODY_COUNT;
            double radius = 1.0e11;
            bodies.add(new BodySpec("b" + i, "天体" + i, "#4d96ff", 1.0e29,
                    Vector3.of(radius * Math.cos(angle), radius * Math.sin(angle), 0),
                    Vector3.of(-1.0e4 * Math.sin(angle), 1.0e4 * Math.cos(angle), 0)));
        }
        SimulationConfig config = new SimulationConfig("二十体", bodies, 3600.0,
                PhysicalConstants.GRAVITATIONAL_CONSTANT, 1.0e7, 1000L, null);
        SimulationState state = NBodyIntegrator.initialState(config);
        for (int i = 0; i < 1000; i++) {
            state = NBodyIntegrator.step(config, state).state();
        }
        assertEquals(1000L, state.step());
        for (BodyState body : state.bodies()) {
            assertTrue(body.position().isFinite() && body.velocity().isFinite());
        }
    }

    @Test
    @DisplayName("模拟时间随步长累加")
    void accumulatesSimulationTime() {
        SimulationConfig config = twoBodyCircularOrbit();
        SimulationState state = NBodyIntegrator.initialState(config);
        for (int i = 0; i < 10; i++) {
            state = NBodyIntegrator.step(config, state).state();
        }
        assertEquals(10 * config.timeStepSeconds(), state.simulationTimeSeconds(), 1e-9);
        assertNotEquals(config.bodies().get(1).position(), state.bodies().get(1).position());
    }
}