package com.routiqo.core.spot.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Lifetimes and server-time rules for Spot contributions (POSTS_AND_SIGNALS_SPEC, Lifetimes and
 * "Still true?"). Client clocks and retries can only shorten an item's life, never extend it.
 */
public record ContributionLife(Duration base, Duration maximum) {
    /** A capture time more than this far after arrival is rejected as a future capture. */
    public static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(2);

    public static final ContributionLife SHORT_SIGNAL =
            new ContributionLife(Duration.ofMinutes(60), Duration.ofHours(2));
    public static final ContributionLife DAY_SIGNAL =
            new ContributionLife(Duration.ofHours(24), Duration.ofHours(36));
    public static final ContributionLife TRAFFIC_POST =
            new ContributionLife(Duration.ofMinutes(90), Duration.ofHours(3));
    public static final ContributionLife PLACE_POST =
            new ContributionLife(Duration.ofHours(24), Duration.ofHours(36));

    public ContributionLife {
        if (base == null || maximum == null || base.isNegative() || base.isZero()
                || maximum.compareTo(base) < 0)
            throw new IllegalArgumentException("Invalid contribution life");
    }

    public static ContributionLife forSignal(SpotCategory category) {
        return switch (category) {
            case TRAFFIC, QUEUE -> SHORT_SIGNAL;
            case FOOD, FUEL, RESTROOM -> DAY_SIGNAL;
        };
    }

    public static ContributionLife forPost(PostType type) {
        return type == PostType.TRAFFIC ? TRAFFIC_POST : PLACE_POST;
    }

    /** Times for a new contribution, or an exception naming why it is refused. */
    public Timing timing(Instant capturedAt, Instant receivedAt) {
        if (capturedAt == null || receivedAt == null)
            throw new IllegalArgumentException("Capture time required");
        if (capturedAt.isAfter(receivedAt.plus(FUTURE_TOLERANCE)))
            throw new IllegalArgumentException("Capture time is in the future");
        Instant effective = capturedAt.isBefore(receivedAt) ? capturedAt : receivedAt;
        Instant expires = effective.plus(base);
        if (!expires.isAfter(receivedAt)) throw new ContributionTooOld();
        return new Timing(effective, expires, effective.plus(maximum));
    }

    /** "Still true": max(expiry, min(vote + base/2, created + maximum)); never shortens. */
    public Instant stillTrue(Instant currentExpiry, Instant voteAt, Instant effectiveCreated) {
        Instant extended = voteAt.plus(base.dividedBy(2));
        Instant cap = effectiveCreated.plus(maximum);
        Instant candidate = extended.isBefore(cap) ? extended : cap;
        return candidate.isAfter(currentExpiry) ? candidate : currentExpiry;
    }

    public record Timing(Instant effectiveCreated, Instant expiresAt, Instant maxExpiresAt) {}

    /** The item's life has already passed on arrival: "Too old to post; it wasn't sent." */
    public static final class ContributionTooOld extends RuntimeException {
        public ContributionTooOld() { super(null, null, false, false); }
    }
}
