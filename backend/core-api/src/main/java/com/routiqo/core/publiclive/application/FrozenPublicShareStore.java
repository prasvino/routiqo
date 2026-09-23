package com.routiqo.core.publiclive.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Internal V2 ledger. Calls require the existing owned-journey transaction. */
public interface FrozenPublicShareStore {
    record Manifest(UUID catalogVersion, Set<UUID> anchorIds,
            Instant startsAt, Instant endsAt) {
        public Manifest { anchorIds = Set.copyOf(anchorIds); }
    }

    record Acknowledgement(UUID pilotId, UUID ownerActorId, UUID journeyId,
            UUID commandId, UUID requestId, String publicKey, Instant claimedAt) {
        @Override public String toString() { return "FrozenPublicShareAcknowledgement[private]"; }
    }

    Manifest manifest(UUID pilotId);

    /** Owner-scoped exact retry, including after private evidence has stopped. */
    Acknowledgement findOwner(UUID pilotId, UUID actorId, UUID journeyId,
            UUID commandId, UUID requestId);

    Acknowledgement freeze(UUID pilotId, UUID personRef, UUID actorId,
            UUID journeyId, UUID commandId, UUID requestId, String publicKey);
}
