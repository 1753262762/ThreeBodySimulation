package com.threebody.web.dto;

import com.threebody.app.domain.RetryStrategy;

/** 创建对照实验时客户端提交的最小来源声明。 */
public record ExperimentRetryContextRequest(
        String sourceExperimentId,
        String recommendationCode,
        RetryStrategy strategy) {
}
