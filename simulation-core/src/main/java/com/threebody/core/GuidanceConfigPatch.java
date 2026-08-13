package com.threebody.core;

/** 引导动作允许自动修改的数值控制项。 */
public record GuidanceConfigPatch(Double timeStepSeconds, Long maxSteps) {
}
