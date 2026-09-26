package com.routiqo.core.spot.domain;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Activity for the requested Spots that exist in the current catalog (SPOTS_SPEC, Spot state;
 * POSTS_AND_SIGNALS_SPEC). Signals appear only as unattributed summaries; posts carry a per-room
 * alias; no account ID is ever part of it. The requested IDs are never retained.
 */
public record SpotActivity(Instant serverTime, UUID catalogVersion, List<Entry> spots) {
    public enum State {
        LIVE, FADING, QUIET;

        public String key() { return name().toLowerCase(Locale.ROOT); }
    }

    public record ValueCount(String value, int reports) {}

    /** A category's current signals: counts per value, the most reported value and its vote ref. */
    public record SignalSummary(UUID ref, String category, String topValue, List<ValueCount> values,
            Instant latestAt, int stillTrue, String viewerVote) {
        public SignalSummary { values = List.copyOf(values); }
    }

    /** {@code hidden} is true only on the viewer's own post that a moderator hid (ADR 0075). */
    public record PostView(UUID ref, String alias, String text, String type, Instant capturedAt,
            Instant expiresAt, int stillTrue, String viewerVote, boolean mine, boolean hidden) {
        @Override public String toString() { return "SpotPostView[private]"; }
    }

    public record Highlight(String text, Instant createdAt) {
        @Override public String toString() { return "SpotHighlight[private]"; }
    }

    public record Entry(UUID id, State state, List<SignalSummary> signals, List<PostView> posts,
            List<Highlight> highlights, boolean postsTruncated) {
        public Entry(UUID id, State state) {
            this(id, state, List.of(), List.of(), List.of(), false);
        }

        public Entry {
            if (id == null || state == null || signals == null || posts == null || highlights == null
                    || posts.size() > 10 || highlights.size() > 3)
                throw new IllegalArgumentException("Invalid Spot activity");
            signals = List.copyOf(signals);
            posts = List.copyOf(posts);
            highlights = List.copyOf(highlights);
        }

        public Entry withPosts(List<PostView> kept, boolean truncated) {
            return new Entry(id, state, signals, kept, highlights, truncated);
        }

        @Override public String toString() { return "SpotActivityEntry[private]"; }
    }

    public SpotActivity {
        if (serverTime == null || catalogVersion == null || spots == null || spots.size() > 20
                || spots.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Invalid Spot activity");
        spots = List.copyOf(spots);
    }

    @Override public String toString() { return "SpotActivity[private]"; }
}
