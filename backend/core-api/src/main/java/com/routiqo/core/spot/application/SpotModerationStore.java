package com.routiqo.core.spot.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage port for the Spots moderator queue and decisions (ADR 0075). Reporter-free: report groups
 * carry counts only. Every method except {@link #openGroups} and {@link #group} runs inside the
 * caller's transaction; lock order is item, then group.
 */
public interface SpotModerationStore {
    enum Kind { POST, SUMMARY }

    record Counts(int falseAlarm, int abuse, int spam, int personalData, int unsafe) {
        /** Unsafe, abuse or personal data puts an item in the urgent tier. */
        public boolean urgent() { return unsafe + abuse + personalData > 0; }
    }

    record Group(UUID ref, UUID itemRef, Instant windowStart, UUID spotId, Kind kind, Counts counts,
            Instant latest, long latestSequence, Long closedThrough, String decision, Long notUpheldThrough,
            Instant openSince) {
        public boolean open() { return closedThrough == null || latestSequence > closedThrough; }
        @Override public String toString() { return "SpotReportGroup[private]"; }
    }

    /** Keyset position: urgent tier first, newest report first, then ref. */
    record Cursor(boolean urgent, long sequence, UUID ref) {}

    /** The reported post as moderators see it; never its author. */
    record PostItem(String text, String type, String alias, String state, Instant captured, Instant expires,
            boolean hidden) {
        @Override public String toString() { return "SpotModerationPost[private]"; }
    }

    /** The signals of one reported summary incident, by count only. */
    record SummaryItem(String category, String value, Instant captured, Instant expires, int active, int hidden,
            List<UUID> signals) {
        public SummaryItem { signals = List.copyOf(signals); }
        @Override public String toString() { return "SpotModerationSummary[private]"; }
    }

    record Votes(int stillTrue, int noLongerTrue) {}

    record StoredAction(String action, UUID reportRef, String reason, String fingerprint) {}

    List<Group> openGroups(Optional<Cursor> after, int limit);
    Optional<Group> group(UUID reportRef);
    Optional<PostItem> post(UUID ref, Instant now);
    /** The incident's evidence signals: in the reported summary, created between its window and last report. */
    Optional<SummaryItem> summary(Group group, Instant now);
    Votes votes(UUID itemRef);
    void recordRead(UUID operator, int items, Instant now);

    Optional<StoredAction> action(UUID operator, UUID requestId);
    void recordAction(UUID operator, UUID requestId, StoredAction action, Instant now);
    Optional<PostItem> lockPost(UUID ref, Instant now);
    Optional<SummaryItem> lockSummary(Group group, Instant now);
    Optional<Group> lockGroup(UUID reportRef);

    void hidePost(UUID ref, Instant now);
    void unhidePost(UUID ref);
    void hideSignals(List<UUID> refs, Instant now);
    void unhideSignals(List<UUID> refs);
    /** Ends every active, unhidden signal for this Spot and category now; returns how many. */
    int clearSignals(UUID spotId, String category, Instant now);
    void close(UUID reportRef, long through, String decision, Instant now);
    /** Evidence stops blocking highlights, place posts are reconsidered, and new reporters are counted. */
    void ruleNotUpheld(Group group, List<UUID> evidence, long fromExclusive, Instant now);
    /** Evidence blocks highlights again (the hide upheld the reports). */
    void ruleUpheld(List<UUID> evidence);

    /** Distinct authors of the reported post or summary incident, at most 20. */
    List<UUID> authors(Group group, Instant now);
    /** Reports by this account ruled not upheld since then. */
    int notUpheldReports(UUID reporter, Instant since);
    /** Stores a hashed 30-minute account reference usable only by this operator. */
    void issueAccountRef(UUID operator, UUID account, String tokenHash, Instant now);
    Optional<UUID> accountRef(UUID operator, String tokenHash, Instant now);
}
