package com.routiqo.core.routeupdate.domain;

import java.util.UUID;

/** Exact private context tuple observed before an explicit signal issuance attempt. */
public record SignalIssuanceExpectation(
        UUID contextId, long routeRevision, long consentGeneration) {
    private static final UUID NIL_ID = new UUID(0, 0);

    public SignalIssuanceExpectation {
        if (contextId == null || NIL_ID.equals(contextId)
                || routeRevision < 0 || consentGeneration < 0) {
            throw new IllegalArgumentException("Invalid signal issuance expectation");
        }
    }

    @Override public String toString() { return "SignalIssuanceExpectation[private]"; }
}
