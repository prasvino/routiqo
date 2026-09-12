package com.routiqo.core.routeupdate.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Internal structured evidence, never a public DTO. Construction and temporal freshness do not authorize
 * journey membership, current consent, admission, storage, aggregation or publication.
 */
public record QuickSignal(UUID signalId, UUID actorId, UUID journeyId, UUID anchorId,
        QuickSignalValue value, long consentGeneration, Instant receivedAt, Instant expiresAt) {
    private static final UUID NIL_ID = new UUID(0, 0);
    private static final Duration MAX_LIFETIME = Duration.ofMinutes(15);

    public QuickSignal {
        if (invalidId(signalId) || invalidId(actorId) || invalidId(journeyId) || invalidId(anchorId)
                || value == null || consentGeneration < 0 || receivedAt == null || expiresAt == null)
            throw invalid();
        Duration lifetime = Duration.between(receivedAt, expiresAt);
        if (lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(MAX_LIFETIME) > 0)
            throw invalid();
    }

    /** Temporal freshness only, using the half-open interval [receivedAt, expiresAt). */
    public boolean isCurrent(Instant now) {
        if (now == null) throw new IllegalArgumentException("Evaluation time is required");
        return !now.isBefore(receivedAt) && now.isBefore(expiresAt);
    }

    /** Journey changes do not create another independent contribution in the same actor/anchor/category slot. */
    public boolean sharesContributionSlot(QuickSignal other) {
        return other != null && actorId.equals(other.actorId) && anchorId.equals(other.anchorId)
                && value.category() == other.value.category();
    }

    /** Exact submission fingerprint check; signalId remains the separate command key. */
    public boolean matchesSubmission(UUID actorId, UUID journeyId, UUID anchorId,
            QuickSignalValue value, long consentGeneration) {
        return this.actorId.equals(actorId) && this.journeyId.equals(journeyId)
                && this.anchorId.equals(anchorId) && this.value == value
                && this.consentGeneration == consentGeneration;
    }

    private static boolean invalidId(UUID id) {
        return id == null || NIL_ID.equals(id);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid quick signal");
    }

    @Override public String toString() {
        return "QuickSignal[private]";
    }
}
