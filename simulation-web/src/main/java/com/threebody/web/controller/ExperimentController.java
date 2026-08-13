package com.threebody.web.controller;

import com.threebody.app.domain.EndReason;
import com.threebody.app.domain.Experiment;
import com.threebody.app.domain.ExperimentAction;
import com.threebody.app.domain.ExperimentMetrics;
import com.threebody.app.domain.ExperimentStatus;
import com.threebody.app.domain.ExperimentRetryRequest;
import com.threebody.app.domain.ExperimentSummaryView;
import com.threebody.app.domain.ExperimentView;
import com.threebody.app.domain.Progress;
import com.threebody.app.domain.SimulationEvent;
import com.threebody.app.domain.SimulationEventType;
import com.threebody.app.domain.TrajectoryInfo;
import com.threebody.app.service.ConfigValidationException;
import com.threebody.app.service.ExperimentService;
import com.threebody.app.service.HistorySlice;
import com.threebody.core.BodySpec;
import com.threebody.core.BodyState;
import com.threebody.core.ConfigValidator;
import com.threebody.core.Preset;
import com.threebody.core.Presets;
import com.threebody.core.SimulationConfig;
import com.threebody.core.SimulationState;
import com.threebody.core.ValidationIssue;
import com.threebody.core.ValidationResult;
import com.threebody.core.Vector3;
import com.threebody.web.dto.ApiError;
import com.threebody.web.dto.ConfigRequestMapper;
import com.threebody.web.dto.ExperimentActionRequest;
import com.threebody.web.dto.ExperimentCreateRequest;
import com.threebody.web.dto.ExperimentUpdateRequest;
import com.threebody.web.dto.SimulationConfigRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class ExperimentController {

    private final ExperimentService service;

    public ExperimentController(ExperimentService service) {
        this.service = service;
    }

    // ============================ 预设 ============================

    @GetMapping("/presets")
    public List<Map<String, Object>> listPresets() {
        return Presets.all().stream()
                .map(ExperimentController::toPresetDto)
                .toList();
    }

    // ============================ 配置校验 ============================

    @PostMapping("/configs/validate")
    public Map<String, Object> validateConfig(@RequestBody SimulationConfigRequest body) {
        ConfigRequestMapper.MappedConfig mapped = ConfigRequestMapper.map(body);
        if (!mapped.complete()) {
            return validationFailureResponse(mapped.issues());
        }
        ValidationResult result = ConfigValidator.validate(mapped.config());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid", result.valid());
        response.put("issues", result.issues().stream()
                .map(ExperimentController::toIssueDto).toList());

        if (result.valid() && result.normalizedConfig() != null) {
            response.put("normalizedConfig", toConfigDto(result.normalizedConfig()));
            response.put("estimatedSteps", result.estimatedSteps());
            response.put("configSummary", result.configSummary());
        } else {
            response.put("normalizedConfig", null);
            response.put("estimatedSteps", null);
            response.put("configSummary", null);
        }

        return response;
    }

    // ============================ 实验 CRUD ============================

    @GetMapping("/experiments")
    public List<Map<String, Object>> listExperiments(@RequestParam(name = "status", required = false) String status) {
        List<Experiment> experiments;
        if (status != null && !status.isBlank()) {
            List<ExperimentStatus> statuses = Arrays.stream(status.split(","))
                    .map(String::trim)
                    .map(ExperimentStatus::valueOf)
                    .toList();
            experiments = service.getExperimentsByStatus(statuses);
        } else {
            experiments = service.getExperiments();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < experiments.size(); i++) {
            result.add(toSummaryDto(experiments.get(i), i, service.getStorageBytes(experiments.get(i).id())));
        }
        return result;
    }

    @PostMapping("/experiments")
    public ResponseEntity<Map<String, Object>> createExperiment(
            @RequestBody ExperimentCreateRequest body) {
        ConfigRequestMapper.MappedConfig mapped = ConfigRequestMapper.map(
                body != null ? body.config() : null);
        if (!mapped.complete()) {
            return ResponseEntity.badRequest().body(toErrorDto("VALIDATION_FAILED",
                    "配置校验失败", mapped.issues()));
        }
        ValidationResult vr = ConfigValidator.validate(mapped.config());
        if (!vr.valid()) {
            return ResponseEntity.badRequest().body(toErrorDto("VALIDATION_FAILED",
                    "配置校验失败", vr.issues()));
        }

        ExperimentRetryRequest retryRequest = body.retryContext() == null ? null
                : new ExperimentRetryRequest(body.retryContext().sourceExperimentId(),
                        body.retryContext().recommendationCode(), body.retryContext().strategy());
        Experiment e = service.createExperiment(body.name(), vr.normalizedConfig(), retryRequest);

        Map<String, Object> dto = toExperimentDto(e, service.getQueuePosition(e.id()),
                service.getStorageBytes(e.id()));

        URI location = URI.create("/api/v1/experiments/" + e.id());
        return ResponseEntity.created(location).body(dto);
    }

    @GetMapping("/experiments/{id}")
    public Map<String, Object> getExperiment(@PathVariable("id") String id) {
        Experiment e = service.getExperiment(id);
        if (e == null) throw new ExperimentNotFoundException(id);
        return toExperimentDto(e, service.getQueuePosition(id), service.getStorageBytes(id));
    }

    @PutMapping("/experiments/{id}")
    public Map<String, Object> updateExperiment(@PathVariable("id") String id,
            @RequestBody ExperimentUpdateRequest body) {
        Experiment e = service.getExperiment(id);
        if (e == null) throw new ExperimentNotFoundException(id);
        if (e.status() != ExperimentStatus.QUEUED) {
            throw new ExperimentNotEditableException(e.status());
        }

        String name = body != null ? body.name() : null;
        SimulationConfig config = null;
        if (body != null && body.config() != null) {
            ConfigRequestMapper.MappedConfig mapped = ConfigRequestMapper.map(body.config());
            if (!mapped.complete()) {
                throw new ValidationFailedException(mapped.issues());
            }
            ValidationResult vr = ConfigValidator.validate(mapped.config());
            if (!vr.valid()) {
                throw new ValidationFailedException(vr.issues());
            }
            config = vr.normalizedConfig();
        }

        e = service.updateExperiment(id, name, config);
        return toExperimentDto(e, service.getQueuePosition(id), service.getStorageBytes(id));
    }

    @DeleteMapping("/experiments/{id}")
    public Map<String, Object> deleteExperiment(@PathVariable("id") String id) {
        Experiment e = service.getExperiment(id);
        if (e == null) throw new ExperimentNotFoundException(id);
        long freedBytes = service.deleteExperiment(id);
        return Map.of("id", id, "freedBytes", freedBytes);
    }

    // ============================ 动作 ============================

    @PostMapping("/experiments/{id}/actions")
    public Map<String, Object> submitAction(@PathVariable("id") String id,
            @RequestBody ExperimentActionRequest body) {
        Experiment e = service.getExperiment(id);
        if (e == null) throw new ExperimentNotFoundException(id);

        String actionStr = body != null ? body.action() : null;
        if (actionStr == null) throw new MalformedRequestException("缺少 action 字段");

        ExperimentAction action;
        try {
            action = ExperimentAction.valueOf(actionStr.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new MalformedRequestException("未知动作：" + actionStr);
        }

        SimulationConfig restartConfig = null;
        if (body.config() != null) {
            if (action != ExperimentAction.RESTART) {
                throw new MalformedRequestException("仅 RESTART 可携带 config");
            }
            ConfigRequestMapper.MappedConfig mapped = ConfigRequestMapper.map(body.config());
            if (!mapped.complete()) {
                throw new ValidationFailedException(mapped.issues());
            }
            ValidationResult vr = ConfigValidator.validate(mapped.config());
            if (!vr.valid()) {
                throw new ValidationFailedException(vr.issues());
            }
            restartConfig = vr.normalizedConfig();
        }

        try {
            e = service.submitAction(id, action, restartConfig);
        } catch (ExperimentService.IllegalStateTransitionException ex) {
            throw new IllegalStateTransitionException(ex.getCurrent(), ex.getAction(), ex.getMessage());
        }

        return toExperimentDto(e, service.getQueuePosition(id), service.getStorageBytes(id));
    }

    // ============================ 队列 ============================

    @PatchMapping("/queue")
    public List<Map<String, Object>> reorderQueue(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("experimentIds");
        if (ids == null) throw new MalformedRequestException("缺少 experimentIds 字段");

        try {
            List<Experiment> experiments = service.reorderQueue(ids);
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = 0; i < experiments.size(); i++) {
                result.add(toSummaryDto(experiments.get(i), i, service.getStorageBytes(experiments.get(i).id())));
            }
            return result;
        } catch (ExperimentService.QueueConflictException ex) {
            throw new QueueConflictException(ex.getMessage());
        }
    }

    @GetMapping("/experiments/{id}/history")
    public Map<String, Object> getExperimentHistory(@PathVariable("id") String id,
            @RequestParam(name = "fromStep", required = false, defaultValue = "0") long fromStep,
            @RequestParam(name = "toStep", required = false) Long toStep,
            @RequestParam(name = "maxPoints", required = false, defaultValue = "1000") int maxPoints) {
        if (maxPoints < 2 || maxPoints > 2000) {
            throw new MalformedRequestException("maxPoints 必须在 2～2000 之间");
        }
        Experiment e = service.getExperiment(id);
        if (e == null) throw new ExperimentNotFoundException(id);
        HistorySlice slice;
        try {
            slice = service.readHistory(id, fromStep, toStep, maxPoints);
        } catch (IllegalArgumentException ex) {
            throw new MalformedRequestException(ex.getMessage());
        }
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("points", slice.points().stream().map(ExperimentController::toStateDto).toList());
        dto.put("availableFromStep", slice.availableFromStep());
        dto.put("availableToStep", slice.availableToStep());
        dto.put("archiveSampleStride", slice.archiveSampleStride());
        dto.put("downsampled", slice.downsampled());
        dto.put("currentState", e.state() != null ? toStateDto(e.state()) : null);
        return dto;
    }

    // ============================ 导出 ============================

    @GetMapping("/experiments/{id}/exports/config")
    public ResponseEntity<Map<String, Object>> exportConfig(@PathVariable("id") String id) {
        Experiment e = service.getExperiment(id);
        if (e == null) throw new ExperimentNotFoundException(id);
        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=config-" + e.id() + ".json")
                .body(toConfigDto(e.config()));
    }

    @GetMapping("/experiments/{id}/exports/trajectory")
    public ResponseEntity<String> exportTrajectory(@PathVariable("id") String id) {
        Experiment e = service.getExperiment(id);
        if (e == null) throw new ExperimentNotFoundException(id);
        service.flushTrajectory(id);

        StringBuilder csv = new StringBuilder("step,timeSeconds,bodyId,bodyName,x,y,z,vx,vy,vz\n");

        // 从轨迹归档文件加载历史轨迹点
        List<SimulationState> trajectory = service.getExperimentRepository().loadTrajectory(id);
        SimulationConfig config = e.config();
        java.util.Map<String, String> idToName = config.bodies().stream()
                .collect(Collectors.toMap(BodySpec::id, BodySpec::name, (a, b) -> a));

        if (!trajectory.isEmpty()) {
            for (SimulationState state : trajectory) {
                for (BodyState b : state.bodies()) {
                    csv.append(state.step()).append(',')
                            .append(state.simulationTimeSeconds()).append(',')
                            .append(escapeCsv(b.id())).append(',')
                            .append(escapeCsv(idToName.getOrDefault(b.id(), b.id()))).append(',')
                            .append(b.position().x()).append(',')
                            .append(b.position().y()).append(',')
                            .append(b.position().z()).append(',')
                            .append(b.velocity().x()).append(',')
                            .append(b.velocity().y()).append(',')
                            .append(b.velocity().z()).append('\n');
                }
            }
        } else {
            // 回退：无归档数据时，导出当前内存中的状态
            SimulationState state = e.state();
            if (state != null) {
                for (BodyState b : state.bodies()) {
                    csv.append(state.step()).append(',')
                            .append(state.simulationTimeSeconds()).append(',')
                            .append(escapeCsv(b.id())).append(',')
                            .append(escapeCsv(idToName.getOrDefault(b.id(), b.id()))).append(',')
                            .append(b.position().x()).append(',')
                            .append(b.position().y()).append(',')
                            .append(b.position().z()).append(',')
                            .append(b.velocity().x()).append(',')
                            .append(b.velocity().y()).append(',')
                            .append(b.velocity().z()).append('\n');
                }
            }
        }

        TrajectoryInfo traj = e.trajectoryInfo();
        return ResponseEntity.ok()
                .header("X-Sample-Stride", String.valueOf(traj.sampleStride()))
                .header("X-Sample-Count", String.valueOf(traj.sampleCount()))
                .header("Content-Disposition",
                        "attachment; filename=trajectory-" + e.id() + ".csv")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv"))
                .body(csv.toString());
    }

    @GetMapping("/experiments/{id}/report-data")
    public Map<String, Object> getReportData(@PathVariable("id") String id) {
        Experiment e = service.getExperiment(id);
        if (e == null) throw new ExperimentNotFoundException(id);
        service.flushTrajectory(id);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("experiment", toExperimentDto(e, service.getQueuePosition(id), service.getStorageBytes(id)));
        report.put("unitSystem", "SI");
        report.put("sampleStride", e.trajectoryInfo().sampleStride());
        report.put("samples", buildReportSamples(e));
        report.put("generatedAt", Instant.now().toString());
        return report;
    }

    // ============================ 异常处理 ============================

    @ExceptionHandler(ExperimentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleNotFound(ExperimentNotFoundException ex) {
        return new ApiError("EXPERIMENT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(ExperimentService.ExperimentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleNotFound(ExperimentService.ExperimentNotFoundException ex) {
        return new ApiError("EXPERIMENT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleConflict(IllegalStateTransitionException ex) {
        return new ApiError("ILLEGAL_STATE_TRANSITION", ex.getMessage());
    }

    @ExceptionHandler(ExperimentService.IllegalStateTransitionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleConflict(ExperimentService.IllegalStateTransitionException ex) {
        return new ApiError("ILLEGAL_STATE_TRANSITION", ex.getMessage());
    }

    @ExceptionHandler(ExperimentNotEditableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleNotEditable(ExperimentNotEditableException ex) {
        return new ApiError("EXPERIMENT_NOT_EDITABLE", ex.getMessage());
    }

    @ExceptionHandler(QueueConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleQueueConflict(QueueConflictException ex) {
        return new ApiError("QUEUE_CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(ConfigValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleConfigValidation(ConfigValidationException ex) {
        return new ApiError("VALIDATION_FAILED", ex.getMessage(),
                ex.issues().stream().map(issue -> new ApiError.ValidationIssueDto(
                        issue.field(), issue.code().name(), issue.message(),
                        issue.severity().name(),
                        issue.riskLevel() != null ? issue.riskLevel().name() : null)).toList());
    }

    @ExceptionHandler(ExperimentService.RetryContextException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleRetryContext(ExperimentService.RetryContextException ex) {
        return new ApiError("INVALID_RETRY_CONTEXT", ex.getMessage());
    }

    @ExceptionHandler(ValidationFailedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleValidationFailed(ValidationFailedException ex) {
        return new ApiError("VALIDATION_FAILED", ex.getMessage(), ex.issues());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleUnreadable(HttpMessageNotReadableException ex) {
        return new ApiError("MALFORMED_REQUEST", "请求体不是合法 JSON 或字段类型错误");
    }

    @ExceptionHandler(MalformedRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleBadRequest(MalformedRequestException ex) {
        return new ApiError("MALFORMED_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError handleInternal(Exception ex) {
        return new ApiError("INTERNAL_ERROR", ex.getMessage() != null ? ex.getMessage() : "内部错误");
    }

    // ============================ DTO 映射 ============================

    private static Map<String, Object> toPresetDto(Preset preset) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("key", preset.key().name());
        dto.put("name", preset.name());
        dto.put("description", preset.description());
        dto.put("config", toConfigDto(preset.config()));
        return dto;
    }

    static Map<String, Object> toConfigDto(SimulationConfig config) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("name", config.name());
        dto.put("bodies", config.bodies().stream()
                .map(ExperimentController::toBodySpecDto).toList());
        dto.put("timeStepSeconds", config.timeStepSeconds());
        dto.put("gravitationalConstant", config.gravitationalConstant());
        dto.put("softeningLengthMeters", config.softeningLengthMeters());
        dto.put("maxSteps", config.maxSteps());
        dto.put("targetSimulationTimeSeconds", config.targetSimulationTimeSeconds());
        return dto;
    }

    private static Map<String, Object> toBodySpecDto(BodySpec spec) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", spec.id());
        dto.put("name", spec.name());
        dto.put("color", spec.color());
        dto.put("massKg", spec.massKg());
        dto.put("position", toVector3Dto(spec.position()));
        dto.put("velocity", toVector3Dto(spec.velocity()));
        return dto;
    }

    private static Map<String, Object> toVector3Dto(Vector3 v) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("x", v.x());
        dto.put("y", v.y());
        dto.put("z", v.z());
        return dto;
    }

    private Map<String, Object> toSummaryDto(Experiment e, int queuePosition, long storageBytes) {
        ExperimentSummaryView view = ExperimentSummaryView.from(e, queuePosition, storageBytes);
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", view.id());
        dto.put("name", view.name());
        dto.put("status", view.status().name());
        dto.put("queuePosition", view.queuePosition());
        dto.put("createdAt", view.createdAt().toString());
        dto.put("updatedAt", view.updatedAt().toString());
        dto.put("startedAt", view.startedAt() != null ? view.startedAt().toString() : null);
        dto.put("completedAt", view.completedAt() != null ? view.completedAt().toString() : null);
        dto.put("bodyCount", view.bodyCount());
        dto.put("progress", toProgressDto(view.progress()));
        dto.put("endReason", view.endReason() != null ? view.endReason().name() : null);
        dto.put("storageBytes", view.storageBytes());
        dto.put("errorCode", view.errorCode());
        dto.put("healthStatus", view.healthStatus() != null ? view.healthStatus().name() : null);
        dto.put("lineage", view.lineage());
        return dto;
    }

    private Map<String, Object> toExperimentDto(Experiment e, int queuePosition, long storageBytes) {
        ExperimentView view = ExperimentView.from(e, queuePosition, storageBytes);
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", view.id());
        dto.put("name", view.name());
        dto.put("status", view.status().name());
        dto.put("queuePosition", view.queuePosition());
        dto.put("createdAt", view.createdAt().toString());
        dto.put("updatedAt", view.updatedAt().toString());
        dto.put("startedAt", view.startedAt() != null ? view.startedAt().toString() : null);
        dto.put("completedAt", view.completedAt() != null ? view.completedAt().toString() : null);
        dto.put("bodyCount", view.bodyCount());
        dto.put("progress", toProgressDto(view.progress()));
        dto.put("endReason", view.endReason() != null ? view.endReason().name() : null);
        dto.put("storageBytes", view.storageBytes());
        dto.put("errorCode", view.errorCode());
        dto.put("healthStatus", view.healthReport() != null ? view.healthReport().status().name() : null);
        dto.put("config", toConfigDto(view.config()));
        dto.put("state", view.state() != null ? toStateDto(view.state()) : null);
        dto.put("metrics", view.metrics() != null ? toMetricsDto(view.state() != null ? view.state().step() : 0,
                view.state() != null ? view.state().simulationTimeSeconds() : 0.0, view.metrics()) : null);
        dto.put("healthReport", view.healthReport());
        dto.put("lineage", view.lineage());
        dto.put("trajectory", toTrajectoryInfoDto(view.trajectory()));
        dto.put("events", view.events().stream().map(ExperimentController::toEventDto).toList());
        dto.put("lastSequence", view.lastSequence());
        dto.put("errorMessage", view.errorMessage());
        return dto;
    }

    private static Map<String, Object> toProgressDto(Progress p) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("step", p.step());
        dto.put("simulationTimeSeconds", p.simulationTimeSeconds());
        dto.put("maxSteps", p.maxSteps());
        dto.put("targetSimulationTimeSeconds", p.targetSimulationTimeSeconds());
        dto.put("completionRatio", p.completionRatio());
        dto.put("stepsPerSecond", p.stepsPerSecond());
        dto.put("estimatedRemainingSteps", p.estimatedRemainingSteps());
        return dto;
    }

    private static Map<String, Object> toStateDto(SimulationState state) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("step", state.step());
        dto.put("simulationTimeSeconds", state.simulationTimeSeconds());
        dto.put("bodies", state.bodies().stream()
                .map(b -> {
                    Map<String, Object> bd = new LinkedHashMap<>();
                    bd.put("id", b.id());
                    bd.put("position", toVector3Dto(b.position()));
                    bd.put("velocity", toVector3Dto(b.velocity()));
                    return bd;
                }).toList());
        return dto;
    }

    private static Map<String, Object> toMetricsDto(long step, double time, ExperimentMetrics m) {
        Map<String, Object> dto = new LinkedHashMap<>();
        // step and time not in the record, only from context
        dto.put("kineticEnergyJoules", m.kineticEnergyJoules());
        dto.put("potentialEnergyJoules", m.potentialEnergyJoules());
        dto.put("totalEnergyJoules", m.totalEnergyJoules());
        dto.put("initialTotalEnergyJoules", m.initialTotalEnergyJoules());
        dto.put("relativeEnergyDrift", m.relativeEnergyDrift());
        dto.put("angularMomentum", toVector3Dto(m.angularMomentum()));
        dto.put("angularMomentumMagnitude", m.angularMomentumMagnitude());
        dto.put("linearMomentum", toVector3Dto(m.linearMomentum()));
        dto.put("linearMomentumMagnitude", m.linearMomentumMagnitude());
        dto.put("minimumPairDistanceMeters", m.minimumPairDistanceMeters());
        dto.put("minimumPairBodyIds", m.minimumPairBodyIds());
        dto.put("allTimeMinimumPairDistanceMeters", m.allTimeMinimumPairDistanceMeters());
        dto.put("allTimeMinimumPairDistanceStep", m.allTimeMinimumPairDistanceStep());
        dto.put("stepsPerSecond", m.stepsPerSecond());
        dto.put("elapsedWallClockSeconds", m.elapsedWallClockSeconds());
        return dto;
    }

    private static Map<String, Object> toEventDto(SimulationEvent ev) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("sequence", ev.sequence());
        dto.put("eventId", ev.eventId());
        dto.put("type", ev.type().name());
        dto.put("phase", ev.phase() != null ? ev.phase().name() : null);
        dto.put("step", ev.step());
        dto.put("simulationTimeSeconds", ev.simulationTimeSeconds());
        dto.put("timestamp", ev.timestamp().toString());
        dto.put("message", ev.message());
        dto.put("bodyIds", ev.bodyIds().isEmpty() ? null : ev.bodyIds());
        dto.put("distanceMeters", ev.distanceMeters());
        dto.put("thresholdMeters", ev.thresholdMeters());
        dto.put("triggerDistanceMeters", ev.triggerDistanceMeters());
        dto.put("closestDistanceMeters", ev.closestDistanceMeters());
        dto.put("closestStep", ev.closestStep());
        dto.put("closestSimulationTimeSeconds", ev.closestSimulationTimeSeconds());
        dto.put("midpointPosition", ev.midpointPosition() != null ? toVector3Dto(ev.midpointPosition()) : null);
        dto.put("diagnostic", ev.diagnostic() != null ? toDiagnosticDto(ev.diagnostic()) : null);
        return dto;
    }

    private static Map<String, Object> toDiagnosticDto(com.threebody.app.domain.Diagnostic d) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("code", d.code());
        dto.put("severity", d.severity().name());
        dto.put("causeCategory", d.causeCategory().name());
        dto.put("summary", d.summary());
        dto.put("likelyCauses", d.likelyCauses());
        dto.put("evidence", toEvidenceDto(d.evidence()));
        dto.put("recommendations", d.recommendations());
        return dto;
    }

    private static Map<String, Object> toEvidenceDto(com.threebody.app.domain.DiagnosticEvidence ev) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("timeStepSeconds", ev.timeStepSeconds());
        dto.put("softeningLengthMeters", ev.softeningLengthMeters());
        dto.put("normalizedEnergyError", ev.normalizedEnergyError());
        dto.put("relativeEnergyDrift", ev.relativeEnergyDrift());
        dto.put("minimumPairDistanceMeters", ev.minimumPairDistanceMeters());
        dto.put("speedRatioToEscape", ev.speedRatioToEscape());
        dto.put("directionChangeDegrees", ev.directionChangeDegrees());
        dto.put("rmsRadiusRatio", ev.rmsRadiusRatio());
        dto.put("outwardBodyFraction", ev.outwardBodyFraction());
        dto.put("lastStableStep", ev.lastStableStep());
        dto.put("bodyIds", ev.bodyIds().isEmpty() ? null : ev.bodyIds());
        return dto;
    }

    private static Map<String, Object> toTrajectoryInfoDto(TrajectoryInfo t) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("sampleStride", t.sampleStride());
        dto.put("sampleCount", t.sampleCount());
        dto.put("pointLimit", t.pointLimit());
        dto.put("liveWindowSize", t.liveWindowSize());
        return dto;
    }

    private static Map<String, Object> toIssueDto(ValidationIssue issue) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("field", issue.field());
        dto.put("code", issue.code().name());
        dto.put("message", issue.message());
        dto.put("severity", issue.severity().name());
        dto.put("riskLevel", issue.riskLevel() != null ? issue.riskLevel().name() : null);
        dto.put("guidance", issue.guidance());
        return dto;
    }

    private Map<String, Object> toErrorDto(String code, String message, List<ValidationIssue> issues) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("code", code);
        dto.put("message", message);
        dto.put("timestamp", Instant.now().toString());
        dto.put("issues", issues.stream().map(ExperimentController::toIssueDto).toList());
        return dto;
    }

    private List<Map<String, Object>> buildReportSamples(Experiment e) {
        // 简化报告采样：仅返回当前状态作为单个采样点
        List<Map<String, Object>> samples = new ArrayList<>();
        SimulationState state = e.state();
        if (state != null) {
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("step", state.step());
            sample.put("simulationTimeSeconds", state.simulationTimeSeconds());
            sample.put("bodies", state.bodies().stream().map(b -> {
                Map<String, Object> bd = new LinkedHashMap<>();
                bd.put("id", b.id());
                bd.put("position", toVector3Dto(b.position()));
                bd.put("velocity", toVector3Dto(b.velocity()));
                return bd;
            }).toList());
            ExperimentMetrics m = e.metrics();
            if (m != null) {
                sample.put("totalEnergyJoules", m.totalEnergyJoules());
                sample.put("relativeEnergyDrift", m.relativeEnergyDrift());
                sample.put("angularMomentumMagnitude", m.angularMomentumMagnitude());
                sample.put("minimumPairDistanceMeters", m.minimumPairDistanceMeters());
            }
            samples.add(sample);
        }
        return samples;
    }

    // ============================ 配置解析辅助 ============================

    private Map<String, Object> validationFailureResponse(List<ValidationIssue> issues) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid", false);
        response.put("issues", issues.stream().map(ExperimentController::toIssueDto).toList());
        response.put("normalizedConfig", null);
        response.put("estimatedSteps", null);
        response.put("configSummary", null);
        return response;
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ============================ 控制器异常 ============================

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class ExperimentNotFoundException extends RuntimeException {
        public ExperimentNotFoundException(String id) {
            super("实验不存在：" + id);
        }
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class MalformedRequestException extends RuntimeException {
        public MalformedRequestException(String message) {
            super(message);
        }
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class ValidationFailedException extends RuntimeException {
        private final List<ApiError.ValidationIssueDto> issues;

        public ValidationFailedException(List<ValidationIssue> issues) {
            super("配置校验失败");
            this.issues = issues.stream().map(issue -> new ApiError.ValidationIssueDto(
                    issue.field(), issue.code().name(), issue.message(), issue.severity().name(),
                    issue.riskLevel() != null ? issue.riskLevel().name() : null)).toList();
        }

        public List<ApiError.ValidationIssueDto> issues() {
            return issues;
        }
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    public static class IllegalStateTransitionException extends RuntimeException {
        private final ExperimentStatus current;
        private final ExperimentAction action;

        public IllegalStateTransitionException(ExperimentStatus current, ExperimentAction action, String message) {
            super(message);
            this.current = current;
            this.action = action;
        }

        public ExperimentStatus getCurrent() { return current; }
        public ExperimentAction getAction() { return action; }
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    public static class ExperimentNotEditableException extends RuntimeException {
        public ExperimentNotEditableException(ExperimentStatus status) {
            super(status + " 实验不可编辑");
        }
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    public static class QueueConflictException extends RuntimeException {
        public QueueConflictException(String message) {
            super(message);
        }
    }
}
