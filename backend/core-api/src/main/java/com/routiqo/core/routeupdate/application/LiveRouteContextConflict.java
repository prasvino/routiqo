package com.routiqo.core.routeupdate.application;

/** Cause-free conflict for stale or structurally invalid internal context replacement. */
public final class LiveRouteContextConflict extends RuntimeException {
    public LiveRouteContextConflict() {
        super("Live route context changed");
    }
}
