package com.routiqo.core.moderation.application;

/** Explicit internal retention maintenance; no scheduler or API is implied. */
public interface ContributionRestrictionAuditCleanup {
    int deleteExpired(int limit);
    int purgeExpiredDebits(int limit);
}
