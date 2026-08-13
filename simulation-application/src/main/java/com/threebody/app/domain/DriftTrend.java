package com.threebody.app.domain;

/** 有界近期窗口给出的漂移趋势；趋势本身不提升 Health 等级。 */
public enum DriftTrend {
    STABLE,
    SLOWLY_INCREASING,
    RAPIDLY_INCREASING
}
