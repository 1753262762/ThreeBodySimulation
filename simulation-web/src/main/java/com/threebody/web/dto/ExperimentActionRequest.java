package com.threebody.web.dto;

/**
 * 实验控制动作请求。仅 RESTART 可携带 config。
 */
public record ExperimentActionRequest(String action, SimulationConfigRequest config) {
}
