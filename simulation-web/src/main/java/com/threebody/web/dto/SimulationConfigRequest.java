package com.threebody.web.dto;

import java.util.List;

/**
 * 模拟配置请求。数值字段使用可空包装类型以区分缺失与 0；
 * maxSteps 与 targetSimulationTimeSeconds 本身就是可空结束条件。
 */
public record SimulationConfigRequest(
        String name,
        List<BodySpecRequest> bodies,
        Double timeStepSeconds,
        Double gravitationalConstant,
        Double softeningLengthMeters,
        Long maxSteps,
        Double targetSimulationTimeSeconds) {
}
