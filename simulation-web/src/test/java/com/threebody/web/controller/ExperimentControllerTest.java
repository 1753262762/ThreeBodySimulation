package com.threebody.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.threebody.app.domain.Experiment;
import com.threebody.app.domain.ExperimentStatus;
import com.threebody.app.service.ExperimentService;
import com.threebody.app.service.HistorySlice;
import com.threebody.app.service.SimulationHealthAnalyzer;
import com.threebody.core.BodySpec;
import com.threebody.core.PhysicalConstants;
import com.threebody.core.MetricsCalculator;
import com.threebody.core.NBodyIntegrator;
import com.threebody.core.SimulationConfig;
import com.threebody.core.Vector3;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ExperimentControllerTest {

    @Test
    void createReturns201ForNewExperiment() throws Exception {
        ExperimentService service = mock(ExperimentService.class);
        Experiment experiment = new Experiment("experiment-new", "新实验", config());
        when(service.createOrReuseExperiment(any(), any(), any()))
                .thenReturn(new ExperimentService.ExperimentCreationResult(experiment, false));
        when(service.getQueuePosition("experiment-new")).thenReturn(0);
        when(service.getStorageBytes("experiment-new")).thenReturn(0L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ExperimentController(service)).build();

        mvc.perform(post("/api/v1/experiments")
                        .contentType("application/json")
                        .content(validCreateJson("新实验")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/experiments/experiment-new"))
                .andExpect(jsonPath("$.id").value("experiment-new"));
    }

    @Test
    void createReturns200ForReusedExperiment() throws Exception {
        ExperimentService service = mock(ExperimentService.class);
        Experiment experiment = new Experiment("experiment-existing", "已有实验", config());
        when(service.createOrReuseExperiment(any(), any(), any()))
                .thenReturn(new ExperimentService.ExperimentCreationResult(experiment, true));
        when(service.getQueuePosition("experiment-existing")).thenReturn(-1);
        when(service.getStorageBytes("experiment-existing")).thenReturn(123L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ExperimentController(service)).build();

        mvc.perform(post("/api/v1/experiments")
                        .contentType("application/json")
                        .content(validCreateJson("重复名称")))
                .andExpect(status().isOk())
                .andExpect(header().string("Location", "/api/v1/experiments/experiment-existing"))
                .andExpect(jsonPath("$.id").value("experiment-existing"));
    }

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
    void detailAndSummaryExposeAuthoritativeHealth() throws Exception {
        ExperimentService service = mock(ExperimentService.class);
        Experiment experiment = new Experiment("experiment-1", "Health REST", config());
        var state = NBodyIntegrator.initialState(config());
        var metrics = MetricsCalculator.compute(config(), state,
                MetricsCalculator.totalEnergy(config(), state));
        experiment.setState(state);
        experiment.setHealthReport(new SimulationHealthAnalyzer(config(), state, null)
                .analyze(state, metrics, false));
        when(service.getExperiment("experiment-1")).thenReturn(experiment);
        when(service.getExperiments()).thenReturn(List.of(experiment));
        when(service.getQueuePosition("experiment-1")).thenReturn(0);
        when(service.getStorageBytes("experiment-1")).thenReturn(0L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ExperimentController(service)).build();

        mvc.perform(get("/api/v1/experiments/{id}", "experiment-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthReport.status").value("GOOD"))
                .andExpect(jsonPath("$.healthReport.thresholds.energyWarning").value(0.001));
        mvc.perform(get("/api/v1/experiments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].healthStatus").value("GOOD"));
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
    void validateConfigReturnsSummaryAndStructuredGuidance() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new ExperimentController(mock(ExperimentService.class))).build();

        mvc.perform(post("/api/v1/configs/validate")
                        .contentType("application/json")
                        .content("""
                                {
                                  "bodies": [
                                    {"id":"a","name":"甲","massKg":1e30,"position":{"x":0,"y":0,"z":0},"velocity":{"x":0,"y":0,"z":0}},
                                    {"id":"b","name":"乙","massKg":1e30,"position":{"x":1e8,"y":0,"z":0},"velocity":{"x":0,"y":0,"z":0}}
                                  ],
                                  "timeStepSeconds":3600,
                                  "gravitationalConstant":6.6743e-11,
                                  "softeningLengthMeters":1e6,
                                  "maxSteps":1000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configSummary.estimatedSteps").value(1000))
                .andExpect(jsonPath("$.configSummary.limitingEndCondition").value("MAX_STEPS"))
                .andExpect(jsonPath("$.issues[0].code").value("TIME_STEP_TOO_LARGE"))
                .andExpect(jsonPath("$.issues[0].guidance.primaryAction.mode").value("APPLY_PATCH"));
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

    @Test
    void historyReturnsRangeFields() throws Exception {
        ExperimentService service = mock(ExperimentService.class);
        Experiment experiment = new Experiment("experiment-1", "REST 测试", config());
        experiment.setState(new com.threebody.core.SimulationState(5L, 300.0,
                java.util.List.of()));
        when(service.getExperiment("experiment-1")).thenReturn(experiment);
        when(service.readHistory(anyString(), anyLong(), any(), anyInt())).thenReturn(
                new HistorySlice(java.util.List.of(), 0L, 5L, 1L, false));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ExperimentController(service)).build();

        mvc.perform(get("/api/v1/experiments/{id}/history", "experiment-1")
                        .param("fromStep", "0").param("toStep", "5").param("maxPoints", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableFromStep").value(0))
                .andExpect(jsonPath("$.availableToStep").value(5))
                .andExpect(jsonPath("$.archiveSampleStride").value(1))
                .andExpect(jsonPath("$.downsampled").value(false));
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

    private static String validCreateJson(String name) {
        return """
                {
                  "name": "%s",
                  "config": {
                    "bodies": [
                      {"id":"a","name":"甲","color":"#ffd166","massKg":1e30,"position":{"x":0,"y":0,"z":0},"velocity":{"x":0,"y":0,"z":0}},
                      {"id":"b","name":"乙","color":"#4d96ff","massKg":1e24,"position":{"x":1e11,"y":0,"z":0},"velocity":{"x":0,"y":30000,"z":0}}
                    ],
                    "timeStepSeconds":60,
                    "gravitationalConstant":6.6743e-11,
                    "softeningLengthMeters":1000000,
                    "maxSteps":1000
                  }
                }
                """.formatted(name);
    }
}
