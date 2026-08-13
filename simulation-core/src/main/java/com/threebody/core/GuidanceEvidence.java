package com.threebody.core;

import java.util.List;

/** 一条可供界面解释和单位格式化的结构化证据。 */
public record GuidanceEvidence(
        String code,
        double value,
        Double referenceValue,
        Double ratio,
        List<String> bodyIds) {

    public GuidanceEvidence {
        bodyIds = bodyIds == null ? List.of() : List.copyOf(bodyIds);
    }
}
