package com.routiqo.core.routeupdate.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Internal receipt for immutable private signal evidence. It is neither a public DTO nor proof of
 * authorization, consent, storage eligibility, aggregation eligibility or publication eligibility.
 * Retention is supplied explicitly by the caller and does not establish a production retention policy.
 */
public record QuickSignalReceipt(
        QuickSignal signal,
        UUID contextId,
        long routeRevision,
        Instant retainUntil,
        State state) {
    private static final UUID NIL_ID = new UUID(0, 0);
    private static final Duration MAX_RETENTION = Duration.ofHours(24);

    public enum State {
        ACTIVE,
        WITHDRAWN,
        SUPERSEDED
    }

    public QuickSignalReceipt {
        if (signal == null || contextId == null || NIL_ID.equals(contextId) || routeRevision < 0
                || retainUntil == null || state == null) {
            throw invalid();
        }
        Duration retention = Duration.between(signal.receivedAt(), retainUntil);
        if (retention.isZero() || retention.isNegative() || retention.compareTo(MAX_RETENTION) > 0
                || retainUntil.isBefore(signal.expiresAt())) {
            throw invalid();
        }
    }

    /** Returns temporal receipt retention only; it does not authorize use of the evidence. */
    public boolean isRetainedAt(Instant now) {
        return now != null && !now.isBefore(signal.receivedAt()) && now.isBefore(retainUntil);
    }

    /** Returns internal evidence freshness only; all authorization and consent checks remain external. */
    public boolean isEvidenceCurrent(Instant now) {
        return state == State.ACTIVE && isRetainedAt(now) && signal.isCurrent(now);
    }

    public QuickSignalReceipt withdraw() {
        return transitionTo(State.WITHDRAWN);
    }

    public QuickSignalReceipt supersede() {
        return transitionTo(State.SUPERSEDED);
    }

    /**
     * Compares an authenticated actor-scoped command fingerprint. A match does not make evidence eligible.
     */
    public boolean matchesSubmission(
            UUID actorId,
            UUID journeyId,
            UUID anchorId,
            QuickSignalValue value,
            long consentGeneration,
            UUID contextId,
            long routeRevision) {
        return signal.matchesSubmission(actorId, journeyId, anchorId, value, consentGeneration)
                && this.contextId.equals(contextId)
                && this.routeRevision == routeRevision;
    }

    private QuickSignalReceipt transitionTo(State target) {
        if (state != State.ACTIVE) {
            return this;
        }
        return new QuickSignalReceipt(signal, contextId, routeRevision, retainUntil, target);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid quick signal receipt");
    }

    @Override
    public String toString() {
        return "QuickSignalReceipt[private]";
    }
}
