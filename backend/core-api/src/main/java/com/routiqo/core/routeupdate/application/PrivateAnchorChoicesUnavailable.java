package com.routiqo.core.routeupdate.application;

/** Generic private-read denial without route, identity or catalog diagnostics. */
public final class PrivateAnchorChoicesUnavailable extends RuntimeException {
    public PrivateAnchorChoicesUnavailable() {
        super("Private route choices are unavailable");
    }
}
