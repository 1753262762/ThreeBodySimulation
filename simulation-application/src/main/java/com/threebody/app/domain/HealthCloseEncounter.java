package com.threebody.app.domain;

import java.util.List;

public record HealthCloseEncounter(
        String eventId,
        List<String> bodyIds,
        double distanceMeters,
        long step,
        double simulationTimeSeconds) {

    public HealthCloseEncounter {
        bodyIds = bodyIds == null ? List.of() : List.copyOf(bodyIds);
    }
}
