package com.routiqo.core.publiclive.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Owner-only V3 candidate ledger; never a public projection or a V2 claim. */
public interface CommunityTrafficCandidateStore {
    record Candidate(UUID candidateId, UUID actorId, UUID journeyId, UUID commandId,
            UUID requestId, UUID anchorId, String trafficValue, Instant windowStart,
            UUID catalogVersion, long consentGeneration, Instant receivedAt,
            Instant acceptedAt, Instant expiresAt, State state) {
        public Instant windowEndsAt() { return windowStart.plusSeconds(300); }
    }
    enum State { ACTIVE, STOPPED }

    Candidate find(UUID actorId, UUID commandId);
    Candidate insert(Candidate candidate);
    Candidate stop(UUID actorId, UUID journeyId, UUID commandId, Instant at);
    List<Candidate> recent(UUID actorId, Instant since);
}
