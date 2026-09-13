package com.routiqo.core.routeupdate.domain;

import java.util.Optional;

/** Private binding result; it is neither a public capability nor publication permission. */
public record RouteBindingOutcome(Status status, Optional<StoredLiveRouteContext> context) {
    public enum Status { BOUND, NO_ROUTE, NO_ELIGIBLE_ANCHORS }

    public RouteBindingOutcome {
        if (status == null || context == null
                || (status == Status.BOUND) != context.isPresent()) {
            throw new IllegalArgumentException("Invalid route binding outcome");
        }
    }

    public static RouteBindingOutcome bound(StoredLiveRouteContext context) {
        return new RouteBindingOutcome(Status.BOUND, Optional.ofNullable(context));
    }

    public static RouteBindingOutcome empty(Status status) {
        return new RouteBindingOutcome(status, Optional.empty());
    }

    @Override public String toString() { return "RouteBindingOutcome[private]"; }
}
