package com.routiqo.core.routeupdate.application;

import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.routeupdate.domain.RouteBindingAttempt;
import com.routiqo.core.routeupdate.domain.StoredLiveRouteContext;
import java.util.Optional;
import java.util.UUID;

/**
 * Route Update-owned participant. Every method requires the existing synchronous account and
 * owned-journey transaction; callers lock consent and context before entering this participant.
 */
public interface RouteBindingAttemptParticipant {
    RouteBindingAttempt reserve(Journey journey, long consentGeneration, UUID catalogVersion,
            Optional<UUID> expectedContextId, UUID attemptId);

    RouteBindingAttempt consume(RouteBindingAttempt expected,
            Optional<StoredLiveRouteContext> currentContext, UUID resultCatalogVersion);

    void invalidatePending(UUID actorId);
}
