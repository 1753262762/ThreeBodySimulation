package com.threebody.core;

import java.util.List;

/** 风险背后的观察、影响、证据和可选动作。 */
public record ValidationGuidance(
        String observation,
        String impact,
        List<GuidanceEvidence> evidence,
        GuidanceAction primaryAction,
        List<GuidanceAction> alternatives) {

    public ValidationGuidance {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    }
}
