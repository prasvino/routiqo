package com.routiqo.core.routeupdate.domain;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Versioned operator-curated anchors. No actor, route geometry or labels are stored. */
public record RouteAnchorCatalog(UUID version, List<RouteAnchor> anchors) {
    private static final UUID NIL_ID = new UUID(0, 0);

    public RouteAnchorCatalog {
        if (version == null || NIL_ID.equals(version) || anchors == null
                || anchors.isEmpty() || anchors.size() > 512
                || anchors.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Invalid route anchor catalog");
        }
        anchors = List.copyOf(anchors);
        var ids = new HashSet<UUID>();
        for (RouteAnchor anchor : anchors) {
            if (!ids.add(anchor.anchorId())) {
                throw new IllegalArgumentException("Invalid route anchor catalog");
            }
        }
    }

    @Override public String toString() { return "RouteAnchorCatalog[private]"; }
}
