package com.threebody.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * API 错误响应，与 OpenAPI ApiError 对应。
 */
public record ApiError(
        String code,
        String message,
        Instant timestamp,
        List<ValidationIssueDto> issues) {

    public ApiError(String code, String message) {
        this(code, message, Instant.now(), null);
    }

    public ApiError(String code, String message, List<ValidationIssueDto> issues) {
        this(code, message, Instant.now(), issues);
    }

    public record ValidationIssueDto(String field, String code, String message, String severity) {}
}
