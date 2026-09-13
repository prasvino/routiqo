package com.routiqo.core.routeupdate.application;

public final class SignalStorageDenied extends SecurityException {
    public SignalStorageDenied() {
        super("Signal command denied");
    }
}
