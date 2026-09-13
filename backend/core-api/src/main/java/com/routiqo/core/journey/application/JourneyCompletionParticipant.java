package com.routiqo.core.journey.application;

import com.routiqo.core.journey.domain.Journey;

/**
 * Synchronous participant in an existing account-and-journey completion transaction. Implementations
 * must use the same datasource, transaction and thread and must not call providers or enter authority again.
 */
public interface JourneyCompletionParticipant {
    void onCompleted(Journey completedJourney);
}
