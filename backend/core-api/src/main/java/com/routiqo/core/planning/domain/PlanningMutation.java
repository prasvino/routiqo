package com.routiqo.core.planning.domain;

import java.util.Objects;
import java.util.UUID;

/** An explicit request to replace the account copy, guarded by the version the traveller last saw. */
public record PlanningMutation(PlanningDocument document, long expectedVersion, UUID mutationId) {
    public PlanningMutation {
        Objects.requireNonNull(document, "Planning document is required");
        if (expectedVersion < 0 || expectedVersion > AccountPlanningCopy.MAX_EXPECTED_VERSION)
            throw new IllegalArgumentException("Invalid expected planning version");
        if (mutationId == null) throw new IllegalArgumentException("Mutation identifier is required");
    }

    @Override public String toString() { return "PlanningMutation[private]"; }
}
