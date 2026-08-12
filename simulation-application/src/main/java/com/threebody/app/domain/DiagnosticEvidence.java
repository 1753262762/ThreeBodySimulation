package com.threebody.app.domain;

import java.util.List;

/**
 * 运行时诊断证据。所有字段均可空，只填写与本次诊断相关的证据项；单位均为 SI。
 */
public record DiagnosticEvidence(
        Double timeStepSeconds,
        Double softeningLengthMeters,
        Double normalizedEnergyError,
        Double relativeEnergyDrift,
        Double minimumPairDistanceMeters,
        Double speedRatioToEscape,
        Double directionChangeDegrees,
        Double rmsRadiusRatio,
        Double outwardBodyFraction,
        Long lastStableStep,
        List<String> bodyIds) {

    public DiagnosticEvidence {
        bodyIds = bodyIds == null ? List.of() : List.copyOf(bodyIds);
    }
}
