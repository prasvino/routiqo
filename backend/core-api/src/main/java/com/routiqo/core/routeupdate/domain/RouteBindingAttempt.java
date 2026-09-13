package com.routiqo.core.routeupdate.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Private durable fence for one provider-backed route binding operation. */
public record RouteBindingAttempt(UUID attemptId, UUID actorId, UUID journeyId,
        long consentGeneration, UUID catalogVersion, Optional<UUID> expectedContextId,
        Instant issuedAt, Instant deadline, State state) {
    public enum State { PENDING, CONSUMED, INVALIDATED }
    private static final UUID NIL_ID = new UUID(0, 0);
    private static final Duration MAX_LIFETIME = Duration.ofSeconds(90);

    public RouteBindingAttempt {
        if (invalid(attemptId) || invalid(actorId) || invalid(journeyId)
                || consentGeneration < 0 || invalid(catalogVersion)
                || expectedContextId == null || expectedContextId.filter(RouteBindingAttempt::invalid).isPresent()
                || issuedAt == null || deadline == null || state == null) {
            throw new IllegalArgumentException("Invalid route binding attempt");
        }
        Duration lifetime;
        try {
            lifetime = Duration.between(issuedAt, deadline);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid route binding attempt");
        }
        if (lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(MAX_LIFETIME) > 0) {
            throw new IllegalArgumentException("Invalid route binding attempt");
        }
        expectedContextId = expectedContextId.map(value -> value);
    }

    public boolean isCurrentAt(Instant now) {
        return now != null && state == State.PENDING && !now.isBefore(issuedAt) && now.isBefore(deadline);
    }

    public RouteBindingAttempt consume() {
        return state == State.PENDING
                ? new RouteBindingAttempt(attemptId, actorId, journeyId, consentGeneration,
                        catalogVersion, expectedContextId, issuedAt, deadline, State.CONSUMED)
                : this;
    }

    private static boolean invalid(UUID id) {
        return id == null || NIL_ID.equals(id);
    }

    @Override public String toString() { return "RouteBindingAttempt[private]"; }
}
