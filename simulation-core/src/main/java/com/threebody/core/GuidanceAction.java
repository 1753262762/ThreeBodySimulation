package com.threebody.core;

/** 用户可以采取的首选或替代引导动作。 */
public record GuidanceAction(
        String code,
        GuidanceActionMode mode,
        String label,
        String rationale,
        String tradeoff,
        GuidanceConfigPatch configPatch,
        GuidanceAdjustmentPolicy adjustmentPolicy) {
}
