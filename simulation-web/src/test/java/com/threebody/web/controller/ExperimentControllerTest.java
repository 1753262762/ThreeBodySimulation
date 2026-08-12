package com.threebody.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.threebody.app.domain.Experiment;
import com.threebody.app.domain.ExperimentStatus;
import com.threebody.app.service.ExperimentService;
import com.threebody.core.BodySpec;
import com.threebody.core.PhysicalConstants;
import com.threebody.core.SimulationConfig;
import com.threebody.core.Vector3;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ExperimentControllerTest {

    @Test
    void pathVariableBindsWithoutCompilerParameterMetadata() throws Exception {
        ExperimentService service = mock(ExperimentService.class);
        Experiment experiment = new Experiment("experiment-1", "REST 测试", config());
        when(service.getExperiment("experiment-1")).thenReturn(experiment);
        when(service.getQueuePosition("experiment-1")).thenReturn(0);
        when(service.getStorageBytes("experiment-1")).thenReturn(0L);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new ExperimentController(service))
                .build();

        mvc.perform(get("/api/v1/experiments/{id}", "experiment-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("experiment-1"))
                .andExpect(jsonPath("$.name").value("REST 测试"));
    }

    @Test
    void malformedJsonBodyReturnsMalformedRequest() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ExperimentController(mock(ExperimentService.class))).build();

        mvc.perform(post("/api/v1/experiments")
                        .contentType("application/json")
                        .content("{这不是合法 JSON"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void missingConfigFieldReturnsValidationFailedWithIssues() throws Exception {
        ExperimentService service = mock(ExperimentService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ExperimentController(service)).build();

        mvc.perform(post("/api/v1/experiments")
                        .contentType("application/json")
                        .content("{\"name\":\"缺配置\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.issues").isArray());
    }

    @Test
    void invalidFieldReturnsValidationFailedWithIssueDetail() throws Exception {
        ExperimentService service = mock(ExperimentService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ExperimentController(service)).build();

        mvc.perform(post("/api/v1/experiments")
                        .contentType("application/json")
                        .content("""
                                {"config": {
                                  "bodies": [
                                    {"name":"甲","massKg":0,"position":{"x":0,"y":0,"z":0},"velocity":{"x":0,"y":0,"z":0}},
                                    {"name":"乙","massKg":1e30,"position":{"x":1e11,"y":0,"z":0},"velocity":{"x":0,"y":0,"z":0}}
                                  ],
                                  "timeStepSeconds":3600,
                                  "gravitationalConstant":6.6743e-11,
                                  "softeningLengthMeters":1e6,
                                  "maxSteps":1000
                                }}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.issues[0].severity").value("ERROR"));
    }

    @Test
    void updateQueuedExperimentValidatesConfig() throws Exception {
        ExperimentService service = mock(ExperimentService.class);
        Experiment experiment = new Experiment("experiment-1", "REST 测试", config());
        when(service.getExperiment("experiment-1")).thenReturn(experiment);
        when(service.getQueuePosition("experiment-1")).thenReturn(0);
        when(service.getStorageBytes("experiment-1")).thenReturn(0L);
        when(service.updateExperiment(anyString(), any(), any())).thenReturn(experiment);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ExperimentController(service)).build();

        mvc.perform(put("/api/v1/experiments/{id}", "experiment-1")
                        .contentType("application/json")
                        .content("{\"name\":\"新名称\",\"config\":{\"bad\":true}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private static SimulationConfig config() {
        return new SimulationConfig(
                "REST 测试",
                List.of(
                        new BodySpec("a", "A", "#ffffff", 1.0e30,
                                Vector3.ZERO, Vector3.ZERO),
                        new BodySpec("b", "B", "#ffffff", 1.0e20,
                                Vector3.of(1.0e11, 0, 0), Vector3.ZERO)),
                60.0,
                PhysicalConstants.GRAVITATIONAL_CONSTANT,
                1.0e6,
                10L,
                null);
    }
}
