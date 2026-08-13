package com.threebody.web.dto;

/**
 * 创建实验请求。
 *
 * @param name   实验名称，可空
 * @param config 模拟配置，必填
 */
public record ExperimentCreateRequest(
        String name,
        SimulationConfigRequest config,
        ExperimentRetryContextRequest retryContext) {
}
