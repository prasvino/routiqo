package com.routiqo.core.routeupdate.domain;

import java.time.Duration;
import java.time.Instant;

/** Internal durable envelope; it is private route intent and never a public DTO or permission. */
public record StoredLiveRouteContext(
        LiveRouteContext context,
        Instant issuedAt,
        Instant expiresAt) {
    private static final Duration MAX_LIFETIME = Duration.ofHours(24);

    public StoredLiveRouteContext {
        if (context == null || issuedAt == null || expiresAt == null) {
            throw invalid();
        }
        Duration lifetime = Duration.between(issuedAt, expiresAt);
        if (lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(MAX_LIFETIME) > 0) {
            throw invalid();
        }
    }

    /** Temporal eligibility only; current journey, consent and admission remain separate checks. */
    public boolean isCurrentAt(Instant now) {
        return now != null && !now.isBefore(issuedAt) && now.isBefore(expiresAt);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid stored live route context");
    }

    @Override
    public String toString() {
        return "StoredLiveRouteContext[private]";
    }
}
