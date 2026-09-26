package com.routiqo.core.spot.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read port for current Spot content. Account IDs come back only so the service can mark the
 * viewer's own posts and exclude authors from vote rules; they never reach a response.
 */
public interface SpotActivityReader {
    record SignalRow(UUID spotId, UUID groupRef, String category, String value, UUID actorId,
            Instant effectiveCreated) {
        @Override public String toString() { return "SpotSignalRow[private]"; }
    }
    record PostRow(UUID spotId, UUID ref, UUID actorId, String type, String text, String alias,
            Instant effectiveCreated, Instant expiresAt) {
        @Override public String toString() { return "SpotPostRow[private]"; }
    }
    record VoteRow(UUID itemRef, UUID actorId, String kind, Instant votedAt) {
        @Override public String toString() { return "SpotVoteRow[private]"; }
    }
    record HighlightRow(UUID spotId, String text, Instant createdAt) {
        @Override public String toString() { return "SpotHighlightRow[private]"; }
    }
    record Contents(List<SignalRow> signals, List<PostRow> posts, List<VoteRow> votes,
            List<HighlightRow> highlights) {}

    /**
     * Unexpired, active content for these Spots: at most 10 newest posts and 3 highlights per Spot.
     * Posts and votes by {@code hiddenAuthors} (the viewer's blocks) are left out before the limit.
     */
    Contents read(List<UUID> spotIds, Instant now, Set<UUID> hiddenAuthors);
}
