package com.routiqo.core.routeupdate.domain;

import com.routiqo.core.routeupdate.domain.QuickSignalValue.Category;
import com.routiqo.core.routing.domain.RouteRequest;
import java.util.Set;
import java.util.UUID;

/** Immutable curated route relevance input. It is not evidence of physical presence. */
public record RouteAnchor(UUID anchorId, RouteRequest.Coordinate location,
        Set<Category> categories) {
    private static final UUID NIL_ID = new UUID(0, 0);

    public RouteAnchor {
        if (anchorId == null || NIL_ID.equals(anchorId) || location == null || categories == null
                || categories.isEmpty() || categories.size() > Category.values().length
                || categories.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Invalid route anchor");
        }
        categories = Set.copyOf(categories);
    }

    @Override public String toString() { return "RouteAnchor[private]"; }
}
