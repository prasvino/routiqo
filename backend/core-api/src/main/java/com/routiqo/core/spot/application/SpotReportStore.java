package com.routiqo.core.spot.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for reports on Spot items (ADR 0072). Report writes run inside the reporter's
 * account-locked transaction, so the rolling quota count is race-free. Account IDs returned here
 * never reach a response or a log.
 */
public interface SpotReportStore {
    enum ItemKind { POST, SUMMARY }

    record StoredReport(UUID itemRef, String reason, Instant createdAt, Instant expiresAt) {
        @Override public String toString() { return "SpotStoredReport[private]"; }
    }
    /** A current (active, unexpired) item and the accounts that wrote it. */
    record Reportable(UUID ref, ItemKind kind, UUID spotId, List<UUID> authors) {
        @Override public String toString() { return "SpotReportable[private]"; }
    }

    Optional<StoredReport> findReport(UUID reporterId, UUID requestId);
    boolean reported(UUID reporterId, UUID itemRef);
    int countReports(UUID reporterId, Instant since);
    /** A current post by ref, or else a signal summary by its group ref with its current signals. */
    Optional<Reportable> reportable(UUID ref, Instant now);
    /** Inserts the reporter row and folds it into the reporter-free group counts. */
    StoredReport insertReport(UUID reporterId, UUID requestId, Reportable item, String reason, Instant now);

    /** The author of a stored post in any state; read without locks, outside a transaction. */
    Optional<UUID> postAuthor(UUID ref);
}
