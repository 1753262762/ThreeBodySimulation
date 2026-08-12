package com.threebody.web.dto;

import com.threebody.core.BodySpec;
import com.threebody.core.SimulationConfig;
import com.threebody.core.ValidationCode;
import com.threebody.core.ValidationIssue;
import com.threebody.core.Vector3;
import java.util.ArrayList;
import java.util.List;

/**
 * 将类型化请求 DTO 映射为领域 {@link SimulationConfig}。
 *
 * <p>数值字段使用可空包装类型以区分"缺失"与"0"；本 Mapper 只负责收集缺失字段问题，
 * 结构完整时才构造领域配置，随后交由 {@link com.threebody.core.ConfigValidator} 做业务校验。
 * Mapper 不产生 HTTP 语义，调用方根据是否构造成功决定返回码。</p>
 */
public final class ConfigRequestMapper {

    /** 映射结果：config 为 null 时表示存在结构缺失，issues 携带缺失字段问题。 */
    public record MappedConfig(SimulationConfig config, List<ValidationIssue> issues) {
        public boolean complete() {
            return config != null;
        }
    }

    private ConfigRequestMapper() {
    }

    public static MappedConfig map(SimulationConfigRequest request) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (request == null) {
            issues.add(ValidationIssue.error("config", ValidationCode.NON_FINITE_VALUE, "缺少 config 字段"));
            return new MappedConfig(null, issues);
        }
        if (request.bodies() == null) {
            issues.add(ValidationIssue.error("config.bodies", ValidationCode.NON_FINITE_VALUE,
                    "缺少 bodies 字段"));
            return new MappedConfig(null, issues);
        }
        if (request.timeStepSeconds() == null) {
            issues.add(ValidationIssue.error("config.timeStepSeconds", ValidationCode.INVALID_TIME_STEP,
                    "缺少 timeStepSeconds 字段"));
        }
        if (request.gravitationalConstant() == null) {
            issues.add(ValidationIssue.error("config.gravitationalConstant",
                    ValidationCode.INVALID_GRAVITATIONAL_CONSTANT, "缺少 gravitationalConstant 字段"));
        }
        if (request.softeningLengthMeters() == null) {
            issues.add(ValidationIssue.error("config.softeningLengthMeters",
                    ValidationCode.INVALID_SOFTENING_LENGTH, "缺少 softeningLengthMeters 字段"));
        }

        List<BodySpec> bodies = new ArrayList<>();
        for (int i = 0; i < request.bodies().size(); i++) {
            String prefix = "config.bodies[" + i + "]";
            BodySpecRequest br = request.bodies().get(i);
            if (br == null) {
                issues.add(ValidationIssue.error(prefix, ValidationCode.NON_FINITE_VALUE, "天体不能为空"));
                continue;
            }
            Vector3 position = mapVector(prefix + ".position", br.position(), issues);
            Vector3 velocity = mapVector(prefix + ".velocity", br.velocity(), issues);
            if (br.massKg() == null) {
                issues.add(ValidationIssue.error(prefix + ".massKg", ValidationCode.INVALID_MASS,
                        "缺少 massKg 字段"));
            }
            if (br.name() == null || br.name().isBlank()) {
                issues.add(ValidationIssue.error(prefix + ".name", ValidationCode.MISSING_BODY_NAME,
                        "天体名称不能为空"));
            }
            bodies.add(new BodySpec(br.id(), br.name(), br.color(),
                    br.massKg() == null ? 0.0 : br.massKg(), position, velocity));
        }

        if (issues.stream().anyMatch(i -> i.severity() == com.threebody.core.ValidationSeverity.ERROR)) {
            return new MappedConfig(null, issues);
        }
        SimulationConfig config = new SimulationConfig(
                request.name(),
                bodies,
                request.timeStepSeconds(),
                request.gravitationalConstant(),
                request.softeningLengthMeters(),
                request.maxSteps(),
                request.targetSimulationTimeSeconds());
        return new MappedConfig(config, issues);
    }

    private static Vector3 mapVector(String prefix, Vector3Request v, List<ValidationIssue> issues) {
        if (v == null) {
            issues.add(ValidationIssue.error(prefix, ValidationCode.NON_FINITE_VALUE, "缺少坐标分量"));
            return Vector3.ZERO;
        }
        if (v.x() == null || v.y() == null || v.z() == null) {
            issues.add(ValidationIssue.error(prefix, ValidationCode.NON_FINITE_VALUE,
                    "缺少坐标分量"));
            return new Vector3(v.x() == null ? 0.0 : v.x(),
                    v.y() == null ? 0.0 : v.y(),
                    v.z() == null ? 0.0 : v.z());
        }
        return new Vector3(v.x(), v.y(), v.z());
    }
}
