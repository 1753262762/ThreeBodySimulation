package com.threebody.web.controller;

import com.threebody.app.domain.ReplayJob;
import com.threebody.app.service.ReplayService;
import com.threebody.core.SimulationState;
import com.threebody.web.dto.ApiError;
import com.threebody.web.dto.ReplayJobCreateRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 精确回放任务接口。POST 精确命中返回 200/COMPLETED，需要重算返回 202/QUEUED。
 */
@RestController
@RequestMapping("/api/v1/experiments/{id}/replay-jobs")
public class ReplayController {

    private final ReplayService replayService;

    public ReplayController(ReplayService replayService) {
        this.replayService = replayService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@PathVariable("id") String id,
            @RequestBody ReplayJobCreateRequest body) {
        if (body == null || body.targetStep() == null) {
            throw new MalformedRequestException("缺少 targetStep 字段");
        }
        ReplayJob job = replayService.create(id, body.targetStep());
        HttpStatus status = job.status() == com.threebody.app.domain.ReplayJobStatus.COMPLETED
                ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(toReplayJobDto(job));
    }

    @GetMapping("/{jobId}")
    public Map<String, Object> get(@PathVariable("id") String id, @PathVariable("jobId") String jobId) {
        ReplayJob job = replayService.get(jobId);
        return toReplayJobDto(job);
    }

    @DeleteMapping("/{jobId}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id, @PathVariable("jobId") String jobId) {
        replayService.delete(jobId);
        return ResponseEntity.noContent().build();
    }

    static Map<String, Object> toReplayJobDto(ReplayJob job) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("jobId", job.jobId());
        dto.put("experimentId", job.experimentId());
        dto.put("targetStep", job.targetStep());
        dto.put("status", job.status().name());
        dto.put("source", job.source() != null ? job.source().name() : null);
        dto.put("baseStep", job.baseStep());
        dto.put("completedSteps", job.completedSteps());
        dto.put("totalSteps", job.totalSteps());
        dto.put("progress", job.progress());
        dto.put("result", job.result() != null ? toStateDto(job.result()) : null);
        dto.put("error", job.error());
        dto.put("createdAt", job.createdAt().toString());
        dto.put("updatedAt", job.updatedAt().toString());
        dto.put("expiresAt", job.expiresAt() != null ? job.expiresAt().toString() : null);
        return dto;
    }

    private static Map<String, Object> toStateDto(SimulationState state) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("step", state.step());
        dto.put("simulationTimeSeconds", state.simulationTimeSeconds());
        dto.put("bodies", state.bodies().stream().map(b -> {
            Map<String, Object> bd = new LinkedHashMap<>();
            bd.put("id", b.id());
            Map<String, Object> pos = new LinkedHashMap<>();
            pos.put("x", b.position().x());
            pos.put("y", b.position().y());
            pos.put("z", b.position().z());
            bd.put("position", pos);
            Map<String, Object> vel = new LinkedHashMap<>();
            vel.put("x", b.velocity().x());
            vel.put("y", b.velocity().y());
            vel.put("z", b.velocity().z());
            bd.put("velocity", vel);
            return bd;
        }).toList());
        return dto;
    }

    @ExceptionHandler(ReplayService.ReplayQueueFullException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ApiError handleQueueFull(ReplayService.ReplayQueueFullException ex) {
        return new ApiError("REPLAY_QUEUE_FULL", ex.getMessage());
    }

    @ExceptionHandler(ReplayService.ReplayJobNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleJobNotFound(ReplayService.ReplayJobNotFoundException ex) {
        return new ApiError("REPLAY_JOB_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleIllegalArgument(IllegalArgumentException ex) {
        return new ApiError("MALFORMED_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(ReplayController.MalformedRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleMalformed(ReplayController.MalformedRequestException ex) {
        return new ApiError("MALFORMED_REQUEST", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class MalformedRequestException extends RuntimeException {
        public MalformedRequestException(String message) {
            super(message);
        }
    }
}
