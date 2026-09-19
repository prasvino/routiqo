package com.routiqo.core.routeupdate.domain;

import com.routiqo.core.routeupdate.domain.QuickSignalValue.Category;
import java.util.Set;
import java.util.UUID;

/** Minimal private display metadata for one anchor in an already-authorized context. */
public record PrivateAnchorChoice(UUID anchorId, String displayLabel, Set<Category> categories) {
    private static final UUID NIL_ID = new UUID(0, 0);

    public PrivateAnchorChoice {
        if (anchorId == null || NIL_ID.equals(anchorId)
                || !RouteAnchor.validDisplayLabel(displayLabel)
                || categories == null || categories.isEmpty()
                || categories.size() > Category.values().length
                || categories.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Invalid private anchor choice");
        }
        categories = Set.copyOf(categories);
    }

    @Override public String toString() { return "PrivateAnchorChoice[private]"; }
}
