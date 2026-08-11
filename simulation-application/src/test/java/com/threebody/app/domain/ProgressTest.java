package com.threebody.app.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.threebody.core.Presets;
import com.threebody.core.SimulationConfig;
import org.junit.jupiter.api.Test;

class ProgressTest {

    @Test
    void usesTheClosestEndConditionWhenBothAreConfigured() {
        SimulationConfig base = Presets.presetA().config();
        double timeStep = base.timeStepSeconds();
        SimulationConfig config = new SimulationConfig(
                base.name(), base.bodies(), timeStep, base.gravitationalConstant(),
                base.softeningLengthMeters(), 100L, 50.0 * timeStep);

        Progress progress = Progress.of(config, 25L, 40.0 * timeStep, null);

        assertEquals(0.8, progress.completionRatio(), 1.0e-12);
        assertEquals(10L, progress.estimatedRemainingSteps());
    }
}
