package com.threebody.app.domain;

/** 实验控制动作，与 REST 契约一一对应。 */
public enum ExperimentAction {
    PAUSE,
    RESUME,
    STEP,
    RESTART,
    CANCEL
}