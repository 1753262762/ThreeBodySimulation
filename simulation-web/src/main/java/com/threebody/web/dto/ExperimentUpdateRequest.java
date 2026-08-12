package com.threebody.web.dto;

/**
 * 编辑实验请求。仅 QUEUED 状态允许；config 可空表示只改名称。
 */
public record ExperimentUpdateRequest(String name, SimulationConfigRequest config) {
}
