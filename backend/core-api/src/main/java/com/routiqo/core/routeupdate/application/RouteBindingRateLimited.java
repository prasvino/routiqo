package com.routiqo.core.routeupdate.application;

/** Private account budget denial. */
public final class RouteBindingRateLimited extends RuntimeException {
    public RouteBindingRateLimited() { super("Route binding rate limited", null, false, false); }
}
