package com.routiqo.core.routeupdate.application;

public final class SignalStorageConflict extends RuntimeException {
    public SignalStorageConflict() {
        super("Signal command changed");
    }
}
