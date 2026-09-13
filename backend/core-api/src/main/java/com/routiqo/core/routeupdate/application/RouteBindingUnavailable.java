package com.routiqo.core.routeupdate.application;

/** Cause-free availability failure at the private route-binding boundary. */
public final class RouteBindingUnavailable extends RuntimeException {
    public RouteBindingUnavailable() { super("Route binding unavailable", null, false, false); }
}
