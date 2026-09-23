package com.routiqo.core.verification.application;

import java.time.Clock;
import java.util.UUID;

/** Persistence participation must remain inside the ordered account transaction. */
public interface VerificationParticipant {
    UUID targetForCase(UUID caseId);
    VerifiedContributorService.Receipt apply(UUID reviewerId, UUID targetId,
            VerifiedContributorService.Command command, Clock clock);
}
