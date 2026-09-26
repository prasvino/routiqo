package com.routiqo.core.moderation.application;

import java.util.UUID;

/**
 * Minimal context a lead sees about an account behind an alias (PILOT_MODERATION_SPEC.md, ADR 0075):
 * never an e-mail, name, Google subject or account identifier. Read inside the caller's transaction.
 */
public interface ModerationAccountFacts {
    record Facts(long accountAgeDays, int completedJourneys, boolean restricted, long restrictionRevision) {}

    Facts read(UUID account);
}
