package com.threebody.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PresetsTest {

    @Test
    void allPresetsAreValidAndRemainFinite() {
        assertEquals(10, Presets.all().size());
        assertEquals(List.of(PresetKey.values()), Presets.all().stream().map(Preset::key).toList());

        for (PresetKey key : PresetKey.values()) {
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

    @Test
    void importedPresetsRetainExpectedBodyCountsAndSettings() {
        assertEquals(20, Presets.presetD().config().bodies().size());
        assertEquals(9, Presets.presetE().config().bodies().size());
        assertEquals(2, Presets.presetF().config().bodies().size());
        assertEquals(3, Presets.presetG().config().bodies().size());
        assertEquals(3, Presets.presetH().config().bodies().size());
        assertEquals(3, Presets.presetI().config().bodies().size());
        assertEquals(4, Presets.presetJ().config().bodies().size());

        assertEquals(21600.0, Presets.presetD().config().timeStepSeconds());
        assertEquals(500_000L, Presets.presetE().config().maxSteps());
        assertEquals(1000.0, Presets.presetF().config().softeningLengthMeters());
        assertEquals(63_072_000.0, Presets.presetG().config().targetSimulationTimeSeconds());
        assertEquals(900.0, Presets.presetH().config().timeStepSeconds());
        assertEquals(1.0e8, Presets.presetI().config().softeningLengthMeters());
        assertEquals(94_608_000.0, Presets.presetJ().config().targetSimulationTimeSeconds());
    }
}
