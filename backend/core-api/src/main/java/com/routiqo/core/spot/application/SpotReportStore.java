package com.routiqo.core.spot.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for reports and room-scoped blocks on Spot items (ADR 0072). Report writes run inside the
 * reporter's account-locked transaction, so the rolling quota count is race-free. Account IDs
 * returned here never reach a response or a log.
 */
public interface SpotReportStore {
    enum ItemKind { POST, SUMMARY }

    record StoredReport(UUID itemRef, String reason, Instant createdAt, Instant expiresAt) {
        @Override public String toString() { return "SpotStoredReport[private]"; }
    }
    /**
     * A current item as one incident: a post, or a signal summary over its current signals. The
     * window starts at the post's creation or the earliest current signal; evidence lists the rows
     * to keep for moderators.
     */
    record Reportable(UUID ref, ItemKind kind, UUID spotId, Instant windowStart, List<UUID> authors,
            List<UUID> evidence) {
        @Override public String toString() { return "SpotReportable[private]"; }
    }
    /** A current post's room and alias, and its author for the account-level block edge. */
    record BlockablePost(UUID authorId, UUID spotId, LocalDate roomDay, String alias) {
        @Override public String toString() { return "SpotBlockablePost[private]"; }
    }

    Optional<StoredReport> findReport(UUID reporterId, UUID requestId);
    boolean reported(UUID reporterId, UUID itemRef, Instant windowStart);
    int countReports(UUID reporterId, Instant since);
    /** The oldest report time at or after {@code since}, for Retry-After once the quota is spent. */
    Optional<Instant> oldestReport(UUID reporterId, Instant since);
    Optional<Reportable> reportable(UUID ref, Instant now);
    /** Inserts the reporter row, folds it into the incident's reporter-free counts and keeps evidence. */
    StoredReport insertReport(UUID reporterId, UUID requestId, Reportable item, String reason, Instant now);

    /** A current post, read without locks outside any transaction. */
    Optional<BlockablePost> blockablePost(UUID ref, Instant now);
    /** Hides this alias in its room for the blocker; idempotent, own transaction. */
    void hideAlias(UUID blockerId, UUID spotId, LocalDate roomDay, String alias, Instant now);
}
