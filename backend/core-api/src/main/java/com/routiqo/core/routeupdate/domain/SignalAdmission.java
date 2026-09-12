package com.routiqo.core.routeupdate.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Internal server snapshot for policy evaluation. It is not a public credential or transport DTO. */
public record SignalAdmission(UUID actorId, UUID journeyId, UUID contextId, UUID anchorId,
        long routeRevision, long consentGeneration, Set<QuickSignalValue.Category> permittedCategories,
        Instant issuedAt, Instant expiresAt) {
    private static final UUID NIL_ID = new UUID(0, 0);
    private static final Duration MAX_LIFETIME = Duration.ofSeconds(90);
    private static final int MAX_CATEGORIES = QuickSignalValue.Category.values().length;

    public SignalAdmission {
        if (invalidId(actorId) || invalidId(journeyId) || invalidId(contextId) || invalidId(anchorId)
                || routeRevision < 0 || consentGeneration < 0 || permittedCategories == null
                || permittedCategories.isEmpty() || permittedCategories.size() > MAX_CATEGORIES
                || issuedAt == null || expiresAt == null)
            throw invalid();
        for (QuickSignalValue.Category category : permittedCategories) if (category == null) throw invalid();
        Duration lifetime = Duration.between(issuedAt, expiresAt);
        if (lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(MAX_LIFETIME) > 0)
            throw invalid();
        permittedCategories = Set.copyOf(permittedCategories);
    }

    /** Temporal validity only; authorization requires the application policy and current authoritative snapshots. */
    public boolean isCurrent(Instant now) {
        return now != null && !now.isBefore(issuedAt) && now.isBefore(expiresAt);
    }

    private static boolean invalidId(UUID id) {
        return id == null || NIL_ID.equals(id);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid signal admission");
    }

    @Override public String toString() {
        return "SignalAdmission[private]";
    }
}
