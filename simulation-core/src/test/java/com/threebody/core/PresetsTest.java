package com.threebody.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PresetsTest {

    @Test
    void scenicPresetsAreValidAndRemainFinite() {
        assertEquals(7, Presets.all().size());

        for (PresetKey key : new PresetKey[] { PresetKey.E, PresetKey.F, PresetKey.G }) {
            SimulationConfig config = Presets.byKey(key).config();
            assertTrue(ConfigValidator.validate(config).valid(), () -> key + " 配置应通过校验");

            SimulationState state = NBodyIntegrator.initialState(config);
            for (int i = 0; i < 2_000; i++) {
                state = NBodyIntegrator.step(config, state).state();
            }
            assertTrue(state.bodies().stream().allMatch(body ->
                    body.position().isFinite() && body.velocity().isFinite()),
                    () -> key + " 长时间积分后应保持有限值");
        }
    }
}
