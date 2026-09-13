package com.routiqo.core.privacy.application;

/** Generic conflict for stale, terminal or exhausted durable consent state. */
public final class PresenceConsentConflict extends RuntimeException {
    public PresenceConsentConflict() {
        super("Presence consent changed");
    }
}
