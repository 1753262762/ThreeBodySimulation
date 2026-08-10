package com.threebody.app.domain;

/**
 * 实验状态机。允许的转换：
 * <pre>
 * QUEUED    -> RUNNING, PAUSED, CANCELLED, FAILED
 * RUNNING   -> PAUSED, COMPLETED, CANCELLED, FAILED
 * PAUSED    -> RUNNING, CANCELLED, FAILED
 * COMPLETED -> (终态)
 * CANCELLED -> (终态)
 * FAILED    -> (终态)
 * </pre>
 * 非法转换由应用层返回 ILLEGAL_STATE_TRANSITION(HTTP 409)。
 */
public enum ExperimentStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED,
    FAILED
}