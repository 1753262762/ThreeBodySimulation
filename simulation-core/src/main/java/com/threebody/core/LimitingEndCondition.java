package com.threebody.core;

/** 预计最先结束运行的条件；相同步数触发时使用 BOTH。 */
public enum LimitingEndCondition {
    MAX_STEPS,
    TARGET_TIME,
    BOTH
}
