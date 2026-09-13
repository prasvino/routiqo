package com.routiqo.core.routeupdate.application;

import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.routeupdate.domain.StoredLiveRouteContext;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Internal storage service; actor IDs and anchor sets must come from trusted server authority. */
public final class LiveRouteContextService {
    private final JourneyWriteAuthority journeys;
    private final LiveRouteContextParticipant contexts;
    private final Supplier<UUID> newContextId;

    public LiveRouteContextService(
            JourneyWriteAuthority journeys, LiveRouteContextParticipant contexts) {
        this(journeys, contexts, UUID::randomUUID);
    }

    LiveRouteContextService(JourneyWriteAuthority journeys, LiveRouteContextParticipant contexts,
            Supplier<UUID> newContextId) {
        this.journeys = Objects.requireNonNull(journeys);
        this.contexts = Objects.requireNonNull(contexts);
        this.newContextId = Objects.requireNonNull(newContextId);
    }

    public Optional<StoredLiveRouteContext> read(UUID actorId, UUID journeyId) {
        return journeys.withOwnedJourney(actorId, journeyId, contexts::read);
    }

    public StoredLiveRouteContext replace(UUID actorId, UUID journeyId, Set<UUID> anchorIds,
            Duration lifetime, Optional<UUID> expectedCurrentContextId) {
        return journeys.withOwnedJourney(actorId, journeyId,
                journey -> contexts.replace(journey, anchorIds, lifetime,
                        expectedCurrentContextId, newContextId.get()));
    }
}
