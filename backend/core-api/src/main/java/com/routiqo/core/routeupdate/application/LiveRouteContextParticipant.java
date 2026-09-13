package com.routiqo.core.routeupdate.application;

import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.routeupdate.domain.StoredLiveRouteContext;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Trusted synchronous participant entered only through JourneyWriteAuthority. Implementations lock their
 * current row, sample server time after that lock and perform no provider or nested-authority work.
 */
public interface LiveRouteContextParticipant {
    Optional<StoredLiveRouteContext> read(Journey journey);

    StoredLiveRouteContext replace(Journey journey, Set<UUID> anchorIds, Duration lifetime,
            Optional<UUID> expectedCurrentContextId, UUID newContextId);
}
