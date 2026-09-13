package com.routiqo.core.routeupdate.application;

/** Private conflict at the internal provider-backed anchor resolution boundary. */
public final class RouteAnchorResolutionConflict extends RuntimeException {
    public RouteAnchorResolutionConflict() {
        super("Route anchor resolution rejected", null, false, false);
    }
}
