package com.routiqo.core.journey.application;

import com.routiqo.core.journey.domain.Journey;

/**
 * Synchronous participant in an existing account-and-journey completion transaction. Implementations
 * must use the same datasource, transaction and thread and must not call providers or enter authority again.
 */
public interface JourneyCompletionParticipant {
    int CONSENT_ORDER = 100;
    int ROUTE_CONTEXT_ORDER = 200;

    void onCompleted(Journey completedJourney);

    /** Lower values run first; production participants must select an explicit unique order. */
    default int completionOrder() {
        return Integer.MAX_VALUE;
    }
}
