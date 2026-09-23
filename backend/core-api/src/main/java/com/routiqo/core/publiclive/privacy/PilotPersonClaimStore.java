package com.routiqo.core.publiclive.privacy;

import java.util.UUID;

/**
 * Disconnected, pilot-wide person contribution reservation. The caller must obtain
 * a stable verified person reference and canonical precommitted public key.
 * Neither account deletion nor changing verification revision refunds a claim.
 */
public interface PilotPersonClaimStore {
    enum Outcome { NEW, REPLAY }

    Outcome claim(UUID pilotId, UUID personRef, UUID requestId, String publicKey);

    /** Deletes at most 100 claims whose pilot ended at least 30 days earlier. */
    int deleteExpired();
}
