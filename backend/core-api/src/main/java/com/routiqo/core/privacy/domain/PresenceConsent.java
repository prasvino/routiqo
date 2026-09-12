package com.routiqo.core.privacy.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Internal state transition policy. Storage must compare-and-set generations across replicas. */
public record PresenceConsent(UUID actorId, UUID journeyId, long generation, boolean sharing, boolean journeyActive) {
    public PresenceConsent {
        Objects.requireNonNull(actorId); Objects.requireNonNull(journeyId);
        if (generation < 0 || (sharing && !journeyActive)) throw new IllegalArgumentException("Invalid consent state");
    }
    public static PresenceConsent initial(UUID actor, UUID journey) {
        return new PresenceConsent(actor, journey, 0, false, true);
    }
    public PresenceConsent changeSharing(boolean enabled) {
        if (enabled && !journeyActive) throw new IllegalStateException("Journey has ended");
        if (enabled == sharing) return this;
        return new PresenceConsent(actorId, journeyId, Math.incrementExact(generation), enabled, journeyActive);
    }
    public PresenceConsent endJourney() {
        if (!journeyActive) return this;
        return new PresenceConsent(actorId, journeyId, Math.incrementExact(generation), false, false);
    }
    public Lease issueLease(long expectedGeneration, Instant now) {
        Objects.requireNonNull(now);
        if (!journeyActive || !sharing || expectedGeneration != generation)
            throw new IllegalStateException("Presence consent is no longer valid");
        return new Lease(actorId, journeyId, generation, now, now.plusSeconds(90));
    }
    public boolean permits(Lease lease, Instant now) {
        Objects.requireNonNull(lease); Objects.requireNonNull(now);
        return sharing && journeyActive && actorId.equals(lease.actorId()) && journeyId.equals(lease.journeyId())
                && generation == lease.generation() && !lease.issuedAt().isAfter(now) && lease.expiresAt().isAfter(now);
    }
    public record Lease(UUID actorId, UUID journeyId, long generation, Instant issuedAt, Instant expiresAt) {
        public Lease {
            Objects.requireNonNull(actorId); Objects.requireNonNull(journeyId);
            Objects.requireNonNull(issuedAt); Objects.requireNonNull(expiresAt);
            if (generation < 0 || !expiresAt.isAfter(issuedAt) || expiresAt.isAfter(issuedAt.plusSeconds(90)))
                throw new IllegalArgumentException("Invalid presence lease");
        }
        @Override public String toString() { return "PresenceLease[private]"; }
    }
    @Override public String toString() { return "PresenceConsent[private]"; }
}
