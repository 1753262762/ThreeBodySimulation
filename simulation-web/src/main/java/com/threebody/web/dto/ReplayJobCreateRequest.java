package com.threebody.web.dto;

/**
 * 回放任务创建请求。
 *
 * @param targetStep 目标步，必须在 [0, currentStep] 内
 */
public record ReplayJobCreateRequest(Long targetStep) {
}
