package com.routiqo.core.verification.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Read-only, fail-closed authority for a currently verified person. */
public interface VerifiedContributorReader {
    Optional<VerifiedContributor> current(UUID accountId, Instant now);

    record VerifiedContributor(UUID personRef, long revision, Instant expiresAt) {}
}
