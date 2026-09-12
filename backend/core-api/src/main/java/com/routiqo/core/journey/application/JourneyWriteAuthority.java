package com.routiqo.core.journey.application;

import com.routiqo.core.journey.domain.Journey;
import java.util.UUID;

/**
 * Trusted synchronous boundary for writes serialized by account and owned journey rows. The actor must
 * come from independent authentication. Callbacks stay on the same datasource, thread and account-owned
 * transaction without providers or nested authority entry. A completed journey supports private replay
 * checks and does not permit new evidence.
 */
public interface JourneyWriteAuthority {
    <T> T withOwnedJourney(UUID actorId, UUID journeyId, Work<T> work);

    @FunctionalInterface
    interface Work<T> {
        T execute(Journey journey);
    }
}
