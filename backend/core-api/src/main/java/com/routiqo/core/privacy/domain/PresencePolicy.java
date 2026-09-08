package com.routiqo.core.privacy.domain;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
/**
 * Internal policy building block. No endpoint exposes this result.
 * Minimum crowd size alone is NOT an end-to-end anti-correlation guarantee.
 */
public final class PresencePolicy {
    private final int minimumCrowd;
    public PresencePolicy(int minimumCrowd) {
        if (minimumCrowd < 2) throw new IllegalArgumentException("Individual presence cannot be exposed");
        this.minimumCrowd = minimumCrowd;
    }
    public record Entry(UUID internalActorId, Instant expiresAt, boolean ghostMode, boolean journeyActive) {
        public Entry { Objects.requireNonNull(internalActorId); Objects.requireNonNull(expiresAt); }
    }
    public OptionalInt aggregate(Collection<Entry> latestEntriesForSegment, Instant now) {
        Objects.requireNonNull(latestEntriesForSegment); Objects.requireNonNull(now);
        // Privacy wins if inconsistent replicas have both visible and ghost entries for one actor.
        var hiddenActors = latestEntriesForSegment.stream().filter(e -> e.ghostMode() || !e.journeyActive()).map(Entry::internalActorId).collect(java.util.stream.Collectors.toSet());
        long count = latestEntriesForSegment.stream()
            .filter(e -> !hiddenActors.contains(e.internalActorId()) && e.expiresAt().isAfter(now))
            .map(Entry::internalActorId).distinct().count();
        return count >= minimumCrowd ? OptionalInt.of(Math.toIntExact(count)) : OptionalInt.empty();
    }
}

