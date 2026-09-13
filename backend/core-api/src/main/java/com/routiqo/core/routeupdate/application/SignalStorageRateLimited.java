package com.routiqo.core.routeupdate.application;

public final class SignalStorageRateLimited extends RuntimeException {
    public SignalStorageRateLimited() {
        super("Signal command rate limited");
    }
}
