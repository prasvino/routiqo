package com.routiqo.core.routeupdate.application;

/** Callable leaf-only physical cleanup; logical expiry remains authoritative before deletion. */
public interface LiveRouteContextExpiryMaintenance {
    int purgeExpired(int limit);
}
