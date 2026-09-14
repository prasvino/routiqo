package com.routiqo.core.routeupdate.application;

import com.routiqo.core.routeupdate.domain.StoredLiveRouteContext;
import java.util.Optional;
import java.util.UUID;

/** Read-only private route-context boundary for authenticated owner-facing transports. */
public interface LiveRouteContextReader {
    Optional<StoredLiveRouteContext> read(UUID actorId, UUID journeyId);
}
