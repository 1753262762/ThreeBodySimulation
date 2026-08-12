package com.threebody.app.domain;

import java.util.List;

/**
 * 结构化运行时诊断。
 *
 * @param code           诊断编码
 * @param severity       严重度
 * @param causeCategory  原因类别
 * @param summary        中文摘要（审慎措辞，不写成确定结论）
 * @param likelyCauses   可能原因列表
 * @param evidence       证据
 * @param recommendations 建议列表
 */
public record Diagnostic(
        String code,
        DiagnosticSeverity severity,
        DiagnosticCauseCategory causeCategory,
        String summary,
        List<String> likelyCauses,
        DiagnosticEvidence evidence,
        List<String> recommendations) {

    public Diagnostic {
        likelyCauses = likelyCauses == null ? List.of() : List.copyOf(likelyCauses);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
    }
}
