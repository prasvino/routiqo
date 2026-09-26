package com.routiqo.core.spot.infrastructure;

import com.routiqo.core.spot.application.SpotModerationStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** PostgreSQL storage for the Spots moderator queue and decisions (ADR 0075). */
final class JdbcSpotModerationStore implements SpotModerationStore {
    private static final String GROUP_COLUMNS = """
            ref, item_ref, window_start, spot_id, item_kind, false_alarm, abuse, spam, personal_data, unsafe,
            latest, latest_sequence, closed_through, decision, not_upheld_through, open_since""";
    private final JdbcTemplate jdbc;

    JdbcSpotModerationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public List<Group> openGroups(Optional<Cursor> after, int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid page size");
        String open = "(closed_through IS NULL OR latest_sequence > closed_through)";
        String urgent = "((unsafe + abuse + personal_data) > 0)";
        if (after.isEmpty()) {
            return jdbc.query("SELECT " + GROUP_COLUMNS + " FROM spot_report_group WHERE " + open
                    + " ORDER BY " + urgent + " DESC, latest_sequence DESC, ref LIMIT ?", JdbcSpotModerationStore::group,
                    limit);
        }
        Cursor cursor = after.get();
        // Urgent rows sort first; within a tier, newest sequence first, then ref.
        return jdbc.query("SELECT " + GROUP_COLUMNS + " FROM spot_report_group WHERE " + open + """
                 AND ((%1$s = ? AND (latest_sequence < ? OR (latest_sequence = ? AND ref > ?)))
                      OR (? AND NOT %1$s))
                ORDER BY %1$s DESC, latest_sequence DESC, ref LIMIT ?
                """.formatted(urgent), JdbcSpotModerationStore::group, cursor.urgent(), cursor.sequence(),
                cursor.sequence(), cursor.ref(), cursor.urgent(), limit);
    }

    @Override public Optional<Group> group(UUID reportRef) {
        return jdbc.query("SELECT " + GROUP_COLUMNS + " FROM spot_report_group WHERE ref = ?",
                JdbcSpotModerationStore::group, reportRef).stream().findFirst();
    }

    @Override public Optional<Group> lockGroup(UUID reportRef) {
        requireTransaction();
        return jdbc.query("SELECT " + GROUP_COLUMNS + " FROM spot_report_group WHERE ref = ? FOR UPDATE",
                JdbcSpotModerationStore::group, reportRef).stream().findFirst();
    }

    @Override public Optional<PostItem> post(UUID ref, Instant now) { return post(ref, ""); }

    @Override public Optional<PostItem> lockPost(UUID ref, Instant now) {
        requireTransaction();
        return post(ref, " FOR UPDATE");
    }

    private Optional<PostItem> post(UUID ref, String lock) {
        return jdbc.query("""
                SELECT text, type, alias, state, effective_created_at, expires_at,
                    moderation_hidden_at IS NOT NULL AS hidden
                FROM spot_post WHERE ref = ?""" + lock, (row, index) -> new PostItem(row.getString("text"),
                        row.getString("type"), row.getString("alias"), row.getString("state"),
                        instant(row, "effective_created_at"), instant(row, "expires_at"), row.getBoolean("hidden")),
                ref).stream().findFirst();
    }

    @Override public Optional<SummaryItem> summary(Group group, Instant now) { return summary(group, now, ""); }

    @Override public Optional<SummaryItem> lockSummary(Group group, Instant now) {
        requireTransaction();
        return summary(group, now, " FOR UPDATE OF s");
    }

    /**
     * The incident's signals: reported evidence in this summary, created from the incident window to its
     * last report. Signals of a later incident in the same summary are separate groups.
     */
    private Optional<SummaryItem> summary(Group group, Instant now, String lock) {
        record Row(UUID ref, String category, String value, String state, Instant created, Instant expires,
                boolean hidden) {}
        List<Row> rows = jdbc.query("""
                SELECT s.ref, g.category, g.value, s.state, s.effective_created_at, s.expires_at,
                    s.moderation_hidden_at IS NOT NULL AS hidden
                FROM spot_signal s JOIN spot_signal_group g ON g.ref = s.group_ref
                WHERE s.group_ref = ? AND s.effective_created_at >= ? AND s.effective_created_at <= ?
                  AND EXISTS (SELECT 1 FROM spot_report_evidence e WHERE e.ref = s.ref)
                ORDER BY s.ref LIMIT 500""" + lock, (row, index) -> new Row(row.getObject("ref", UUID.class),
                        row.getString("category"), row.getString("value"), row.getString("state"),
                        instant(row, "effective_created_at"), instant(row, "expires_at"), row.getBoolean("hidden")),
                group.itemRef(), Timestamp.from(group.windowStart()), Timestamp.from(group.latest()));
        if (rows.isEmpty()) return Optional.empty();
        int active = 0, hidden = 0;
        for (Row row : rows) {
            boolean current = "ACTIVE".equals(row.state()) && row.expires().isAfter(now);
            if (row.hidden()) hidden++;
            else if (current) active++;
        }
        return Optional.of(new SummaryItem(rows.getFirst().category(), rows.getFirst().value(),
                rows.stream().map(Row::created).min(Instant::compareTo).orElseThrow(),
                rows.stream().map(Row::expires).max(Instant::compareTo).orElseThrow(), active, hidden,
                rows.stream().map(Row::ref).toList()));
    }

    @Override public Votes votes(UUID itemRef) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE kind = 'STILL_TRUE')::INTEGER,
                       count(*) FILTER (WHERE kind = 'NO_LONGER_TRUE')::INTEGER
                FROM spot_vote WHERE item_ref = ?
                """, (row, index) -> new Votes(row.getInt(1), row.getInt(2)), itemRef);
    }

    @Override public void recordRead(UUID operator, int items, Instant now) {
        requireTransaction();
        jdbc.update("""
                INSERT INTO spot_moderation_read_audit (id, operator_id, item_count, occurred_at, expires_at)
                VALUES (?, ?, ?, ?, ?::timestamptz + INTERVAL '30 days')
                """, UUID.randomUUID(), operator, items, Timestamp.from(now), Timestamp.from(now));
    }

    @Override public Optional<StoredAction> action(UUID operator, UUID requestId) {
        requireTransaction();
        return jdbc.query("""
                SELECT action, report_ref, reason, fingerprint FROM spot_moderation_action
                WHERE operator_id = ? AND request_id = ? FOR UPDATE
                """, (row, index) -> new StoredAction(row.getString("action"), row.getObject("report_ref", UUID.class),
                        row.getString("reason"), row.getString("fingerprint")), operator, requestId)
                .stream().findFirst();
    }

    @Override public void recordAction(UUID operator, UUID requestId, StoredAction action, Instant now) {
        requireTransaction();
        jdbc.update("""
                INSERT INTO spot_moderation_action (operator_id, request_id, action, report_ref, reason, fingerprint,
                    occurred_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz + INTERVAL '30 days')
                """, operator, requestId, action.action(), action.reportRef(), action.reason(), action.fingerprint(),
                Timestamp.from(now), Timestamp.from(now));
    }

    @Override public void hidePost(UUID ref, Instant now) {
        requireTransaction();
        jdbc.update("UPDATE spot_post SET moderation_hidden_at = ? WHERE ref = ? AND moderation_hidden_at IS NULL",
                Timestamp.from(now), ref);
        // A hidden post disappears everywhere, including a highlight already made from it.
        jdbc.update("DELETE FROM spot_highlight WHERE source_post_ref = ?", ref);
    }

    @Override public void unhidePost(UUID ref) {
        requireTransaction();
        jdbc.update("UPDATE spot_post SET moderation_hidden_at = NULL WHERE ref = ?", ref);
    }

    @Override public void hideSignals(List<UUID> refs, Instant now) {
        requireTransaction();
        jdbc.update("""
                UPDATE spot_signal SET moderation_hidden_at = ?
                WHERE ref = ANY (?) AND state = 'ACTIVE' AND expires_at > ? AND moderation_hidden_at IS NULL
                """, Timestamp.from(now), (Object) refs.toArray(UUID[]::new), Timestamp.from(now));
    }

    @Override public void unhideSignals(List<UUID> refs) {
        requireTransaction();
        jdbc.update("UPDATE spot_signal SET moderation_hidden_at = NULL WHERE ref = ANY (?)",
                (Object) refs.toArray(UUID[]::new));
    }

    @Override public int clearSignals(UUID spotId, String category, Instant now) {
        requireTransaction();
        return jdbc.update("""
                UPDATE spot_signal SET state = 'EXPIRED_EARLY', ended_at = ?, expires_at = LEAST(expires_at, ?)
                WHERE ref IN (SELECT ref FROM spot_signal WHERE spot_id = ? AND category = ? AND state = 'ACTIVE'
                    AND expires_at > ? ORDER BY ref FOR UPDATE)
                """, Timestamp.from(now), Timestamp.from(now), spotId, category, Timestamp.from(now));
    }

    @Override public void close(UUID reportRef, long through, String decision, Instant now) {
        // A decision closes the group it locked; the queue's system closure applies only while still open,
        // so it never overwrites a moderator's concurrent decision.
        boolean system = decision.equals("CLOSED_EVIDENCE_UNAVAILABLE");
        jdbc.update("""
                UPDATE spot_report_group SET closed_through = ?, decision = ?, decided_at = ?
                WHERE ref = ? AND latest_sequence = ?
                """ + (system ? " AND (closed_through IS NULL OR latest_sequence > closed_through)" : ""),
                through, decision, Timestamp.from(now), reportRef, through);
    }

    @Override public void ruleNotUpheld(Group group, List<UUID> evidence, long fromExclusive, Instant now) {
        requireTransaction();
        UUID[] refs = evidence.toArray(UUID[]::new);
        jdbc.update("UPDATE spot_report_evidence SET not_upheld_at = ? WHERE ref = ANY (?) AND not_upheld_at IS NULL",
                Timestamp.from(now), (Object) refs);
        // Place posts get another highlight check; hidden, deleted or traffic posts never qualify.
        jdbc.update("""
                UPDATE spot_post SET highlight_checked = FALSE
                WHERE ref = ANY (?) AND type = 'place' AND state = 'ACTIVE' AND moderation_hidden_at IS NULL
                """, (Object) refs);
        // Reporter rows are kept 7 days; count each report once, never one a hide already upheld.
        jdbc.update("""
                INSERT INTO spot_reporter_not_upheld (reporter_id, decided_at, expires_at)
                SELECT reporter_id, ?, ?::timestamptz + INTERVAL '30 days' FROM spot_report
                WHERE item_ref = ? AND window_start = ? AND review_sequence > ? AND review_sequence <= ?
                ORDER BY review_sequence
                """, Timestamp.from(now), Timestamp.from(now), group.itemRef(), Timestamp.from(group.windowStart()),
                fromExclusive, group.latestSequence());
        jdbc.update("UPDATE spot_report_group SET not_upheld_through = ? WHERE ref = ?",
                group.latestSequence(), group.ref());
    }

    @Override public void ruleUpheld(List<UUID> evidence) {
        requireTransaction();
        jdbc.update("UPDATE spot_report_evidence SET not_upheld_at = NULL WHERE ref = ANY (?) AND not_upheld_at IS NOT NULL",
                (Object) evidence.toArray(UUID[]::new));
    }

    private static Group group(ResultSet row, int index) throws SQLException {
        long closed = row.getLong("closed_through");
        Long closedThrough = row.wasNull() ? null : closed;
        long notUpheld = row.getLong("not_upheld_through");
        Long notUpheldThrough = row.wasNull() ? null : notUpheld;
        return new Group(row.getObject("ref", UUID.class), row.getObject("item_ref", UUID.class),
                instant(row, "window_start"), row.getObject("spot_id", UUID.class),
                Kind.valueOf(row.getString("item_kind")), new Counts(row.getInt("false_alarm"), row.getInt("abuse"),
                        row.getInt("spam"), row.getInt("personal_data"), row.getInt("unsafe")),
                instant(row, "latest"), row.getLong("latest_sequence"), closedThrough, row.getString("decision"),
                notUpheldThrough, instant(row, "open_since"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getTimestamp(column).toInstant();
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Spot moderation writes need the caller's transaction");
    }
}
