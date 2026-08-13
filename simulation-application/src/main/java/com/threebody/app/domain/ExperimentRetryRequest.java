package com.threebody.app.domain;

/** 创建对照实验时由可信适配层传入的最小来源上下文。 */
public record ExperimentRetryRequest(
        String sourceExperimentId,
        String recommendationCode,
        RetryStrategy strategy) {
}
