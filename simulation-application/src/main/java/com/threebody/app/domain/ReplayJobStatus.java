package com.threebody.app.domain;

/** 回放任务状态。 */
public enum ReplayJobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED
}
