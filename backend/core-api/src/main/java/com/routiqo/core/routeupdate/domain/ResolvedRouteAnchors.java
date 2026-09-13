package com.routiqo.core.routeupdate.domain;

import com.routiqo.core.routeupdate.domain.QuickSignalValue.Category;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Private in-memory relevance snapshot. It is not a client capability or publication permission. */
public record ResolvedRouteAnchors(UUID catalogVersion, Status status,
        Map<UUID, Set<Category>> anchors) {
    public enum Status { NO_ROUTE, ROUTE }
    private static final UUID NIL_ID = new UUID(0, 0);

    public ResolvedRouteAnchors {
        if (catalogVersion == null || NIL_ID.equals(catalogVersion) || status == null
                || anchors == null || anchors.size() > 128
                || (status == Status.NO_ROUTE && !anchors.isEmpty())) {
            throw new IllegalArgumentException("Invalid route anchor result");
        }
        var copy = new LinkedHashMap<UUID, Set<Category>>();
        for (var entry : anchors.entrySet()) {
            if (entry.getKey() == null || NIL_ID.equals(entry.getKey()) || entry.getValue() == null
                    || entry.getValue().isEmpty()
                    || entry.getValue().stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("Invalid route anchor result");
            }
            copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        anchors = Map.copyOf(copy);
    }

    public boolean hasRoute() { return status == Status.ROUTE; }

    @Override public String toString() { return "ResolvedRouteAnchors[private]"; }
}
