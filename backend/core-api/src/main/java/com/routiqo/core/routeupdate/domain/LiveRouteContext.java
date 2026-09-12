package com.routiqo.core.routeupdate.domain;

import java.util.Set;
import java.util.UUID;

/** Internal server-owned route context. It contains no geometry or traveller endpoints. */
public record LiveRouteContext(UUID contextId, UUID actorId, UUID journeyId, long revision,
        Set<UUID> anchorIds) {
    private static final UUID NIL_ID = new UUID(0, 0);
    private static final int MAX_ANCHORS = 128;

    public LiveRouteContext {
        if (invalidId(contextId) || invalidId(actorId) || invalidId(journeyId) || revision < 0
                || anchorIds == null || anchorIds.isEmpty() || anchorIds.size() > MAX_ANCHORS)
            throw invalid();
        for (UUID anchorId : anchorIds) if (invalidId(anchorId)) throw invalid();
        anchorIds = Set.copyOf(anchorIds);
    }

    private static boolean invalidId(UUID id) {
        return id == null || NIL_ID.equals(id);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid live route context");
    }

    @Override public String toString() {
        return "LiveRouteContext[private]";
    }
}
