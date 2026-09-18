package com.routiqo.core.moderation.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Private reviewed eligibility snapshot. It does not prove a distinct person or authorize publication. */
public record ContributorAssessment(UUID actorId, long revision, State state,
        UUID assessmentRef, Instant issuedAt, Instant expiresAt) {
    private static final UUID NIL = new UUID(0, 0);
    private static final Duration MAX_VALIDITY = Duration.ofHours(24);

    public enum State { UNASSESSED, ASSESSED, SUSPENDED }

    public ContributorAssessment {
        if (invalid(actorId) || revision < 0 || state == null) throw invalidState();
        if (state == State.ASSESSED) {
            if (revision == Long.MAX_VALUE || invalid(assessmentRef)
                    || !finite(issuedAt) || !finite(expiresAt))
                throw invalidState();
            Duration validity = Duration.between(issuedAt, expiresAt);
            if (validity.isZero() || validity.isNegative()
                    || validity.compareTo(MAX_VALIDITY) > 0) throw invalidState();
        } else if (assessmentRef != null || issuedAt != null || expiresAt != null) {
            throw invalidState();
        }
    }

    public static ContributorAssessment initial(UUID actorId) {
        return new ContributorAssessment(actorId, 0, State.UNASSESSED, null, null, null);
    }

    public boolean eligibleAt(Instant now) {
        return state == State.ASSESSED && finite(now)
                && !now.isBefore(issuedAt) && now.isBefore(expiresAt);
    }

    public ContributorAssessment assess(long expectedRevision, UUID reference,
            Instant issued, Instant expiry) {
        requireExact(expectedRevision);
        if (state == State.SUSPENDED || revision >= Long.MAX_VALUE - 1) throw denied();
        return new ContributorAssessment(actorId, revision + 1, State.ASSESSED,
                reference, issued, expiry);
    }

    /** Revocation may win over a stale enable intent, but a future intent is never accepted. */
    public ContributorAssessment suspend(long expectedRevision) {
        requireNotFuture(expectedRevision);
        if (revision == Long.MAX_VALUE) {
            return state == State.SUSPENDED ? this
                    : new ContributorAssessment(actorId, revision, State.SUSPENDED,
                            null, null, null);
        }
        return new ContributorAssessment(actorId, revision + 1, State.SUSPENDED,
                null, null, null);
    }

    public ContributorAssessment unsuspend(long expectedRevision) {
        requireExact(expectedRevision);
        if (state != State.SUSPENDED || revision >= Long.MAX_VALUE - 1) throw denied();
        return new ContributorAssessment(actorId, revision + 1, State.UNASSESSED,
                null, null, null);
    }

    private void requireExact(long expected) {
        requireNotFuture(expected);
        if (expected != revision) throw denied();
    }

    private void requireNotFuture(long expected) {
        if (expected < 0 || expected > revision) throw denied();
    }

    private static boolean invalid(UUID id) { return id == null || NIL.equals(id); }
    private static boolean finite(Instant at) {
        return at != null && !at.equals(Instant.MIN) && !at.equals(Instant.MAX);
    }
    private static IllegalArgumentException invalidState() {
        return new IllegalArgumentException("Invalid contributor assessment");
    }
    private static IllegalStateException denied() {
        return new IllegalStateException("Contributor assessment transition denied");
    }
    @Override public String toString() { return "ContributorAssessment[private]"; }
}
