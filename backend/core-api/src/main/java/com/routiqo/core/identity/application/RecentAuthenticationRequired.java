package com.routiqo.core.identity.application;

public final class RecentAuthenticationRequired extends SecurityException {
    public RecentAuthenticationRequired() { super("Recent authentication required"); }
}
