package com.routiqo.core.routeupdate.application;

/** Cause-free denial for stale or inconsistent private route-binding state. */
public final class RouteBindingConflict extends RuntimeException {
    public RouteBindingConflict() { super("Route binding changed", null, false, false); }
}
