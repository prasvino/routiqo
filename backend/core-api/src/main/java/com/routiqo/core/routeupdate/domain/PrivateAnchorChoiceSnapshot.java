package com.routiqo.core.routeupdate.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Owner-observed context choices; it grants no write or public visibility authority. */
public record PrivateAnchorChoiceSnapshot(UUID contextId, long revision, long consentGeneration,
        Instant issuedAt, Instant expiresAt, List<PrivateAnchorChoice> choices) {
    private static final UUID NIL_ID = new UUID(0, 0);
    private static final Duration MAX_LIFETIME = Duration.ofHours(24);

    public PrivateAnchorChoiceSnapshot {
        if (contextId == null || NIL_ID.equals(contextId) || revision < 0
                || consentGeneration < 0 || !finite(issuedAt) || !finite(expiresAt)
                || !expiresAt.isAfter(issuedAt)
                || Duration.between(issuedAt, expiresAt).compareTo(MAX_LIFETIME) > 0
                || choices == null
                || choices.isEmpty() || choices.size() > 128
                || choices.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Invalid private anchor choice snapshot");
        }
        choices = List.copyOf(choices);
        var ids = new HashSet<UUID>();
        String previous = null;
        for (PrivateAnchorChoice choice : choices) {
            String current = choice.anchorId().toString();
            if (!ids.add(choice.anchorId()) || previous != null && previous.compareTo(current) >= 0) {
                throw new IllegalArgumentException("Invalid private anchor choice snapshot");
            }
            previous = current;
        }
    }

    private static boolean finite(Instant value) {
        return value != null && !Instant.MIN.equals(value) && !Instant.MAX.equals(value);
    }

    @Override public String toString() { return "PrivateAnchorChoiceSnapshot[private]"; }
}
