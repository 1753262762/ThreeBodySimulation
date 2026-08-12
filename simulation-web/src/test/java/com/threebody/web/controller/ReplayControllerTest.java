package com.threebody.web.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.threebody.app.domain.ReplayJob;
import com.threebody.app.domain.ReplayJobStatus;
import com.threebody.app.domain.ReplaySource;
import com.threebody.app.service.ReplayService;
import com.threebody.core.SimulationState;
import com.threebody.core.BodyState;
import com.threebody.core.Vector3;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReplayControllerTest {

    private static ReplayJob completedJob() {
        Instant now = Instant.now();
        SimulationState result = new SimulationState(42L, 42.0 * 3600,
                List.of(new BodyState("a", Vector3.ZERO, Vector3.ZERO)));
        return new ReplayJob("job-1", "exp-1", 42L, ReplayJobStatus.COMPLETED,
                ReplaySource.RECOMPUTED, 0L, 42L, 42L, 1.0, result, null,
                now, now, now.plusSeconds(600));
    }

    @Test
    void postExactHitReturns200Completed() throws Exception {
        ReplayService service = mock(ReplayService.class);
        when(service.create(anyString(), eq(42L))).thenReturn(completedJob());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReplayController(service)).build();

        mvc.perform(post("/api/v1/experiments/{id}/replay-jobs", "exp-1")
                        .contentType("application/json")
                        .content("{\"targetStep\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.source").value("RECOMPUTED"))
                .andExpect(jsonPath("$.targetStep").value(42));
    }

    @Test
    void getJobReturnsProgress() throws Exception {
        ReplayService service = mock(ReplayService.class);
        when(service.get("job-1")).thenReturn(completedJob());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReplayController(service)).build();

        mvc.perform(get("/api/v1/experiments/{id}/replay-jobs/{jobId}", "exp-1", "job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.progress").value(1.0));
    }

    @Test
    void deleteJobReturns204() throws Exception {
        ReplayService service = mock(ReplayService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReplayController(service)).build();

        mvc.perform(delete("/api/v1/experiments/{id}/replay-jobs/{jobId}", "exp-1", "job-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void queueFullReturns429() throws Exception {
        ReplayService service = mock(ReplayService.class);
        when(service.create(anyString(), eq(99L)))
                .thenThrow(new ReplayService.ReplayQueueFullException("回放队列已满"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReplayController(service)).build();

        mvc.perform(post("/api/v1/experiments/{id}/replay-jobs", "exp-1")
                        .contentType("application/json")
                        .content("{\"targetStep\":99}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("REPLAY_QUEUE_FULL"));
    }
}
