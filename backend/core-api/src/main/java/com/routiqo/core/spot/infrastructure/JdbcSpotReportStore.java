package com.routiqo.core.spot.infrastructure;

import com.routiqo.core.spot.application.SpotReportStore;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** PostgreSQL participant for Spot reports and room-scoped blocks. */
final class JdbcSpotReportStore implements SpotReportStore {
    /** Evidence is kept a fixed 30 days from the first report naming it. */
    private static final String EVIDENCE_LIFE = "30 days";
    private final JdbcTemplate jdbc;

    JdbcSpotReportStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Spot reports need the account transaction");
    }

    @Override public Optional<StoredReport> findReport(UUID reporterId, UUID requestId) {
        requireTransaction();
        return jdbc.query("""
                SELECT item_ref, reason, created_at, expires_at FROM spot_report
                WHERE reporter_id = ? AND request_id = ?
                """, (row, index) -> new StoredReport(row.getObject("item_ref", UUID.class),
                        row.getString("reason"), row.getTimestamp("created_at").toInstant(),
                        row.getTimestamp("expires_at").toInstant()),
                reporterId, requestId).stream().findFirst();
    }

    @Override public boolean reported(UUID reporterId, UUID itemRef, Instant windowStart) {
        requireTransaction();
        return !jdbc.queryForList("""
                SELECT 1 FROM spot_report WHERE reporter_id = ? AND item_ref = ? AND window_start = ?
                """, Integer.class, reporterId, itemRef, Timestamp.from(windowStart)).isEmpty();
    }

    @Override public int countReports(UUID reporterId, Instant since) {
        requireTransaction();
        return jdbc.queryForObject("SELECT count(*) FROM spot_report WHERE reporter_id = ? AND created_at > ?",
                Integer.class, reporterId, Timestamp.from(since));
    }

    @Override public Optional<Instant> oldestReport(UUID reporterId, Instant since) {
        requireTransaction();
        Timestamp oldest = jdbc.queryForObject(
                "SELECT min(created_at) FROM spot_report WHERE reporter_id = ? AND created_at > ?",
                Timestamp.class, reporterId, Timestamp.from(since));
        return Optional.ofNullable(oldest).map(Timestamp::toInstant);
    }

    @Override public Optional<Reportable> reportable(UUID ref, Instant now) {
        requireTransaction();
        Timestamp at = Timestamp.from(now);
        Optional<Reportable> post = jdbc.query("""
                SELECT spot_id, actor_id, effective_created_at FROM spot_post
                WHERE ref = ? AND state = 'ACTIVE' AND expires_at > ? AND moderation_hidden_at IS NULL
                FOR SHARE
                """, (row, index) -> new Reportable(ref, ItemKind.POST, row.getObject("spot_id", UUID.class),
                        row.getTimestamp("effective_created_at").toInstant(),
                        List.of(row.getObject("actor_id", UUID.class)), List.of(ref)), ref, at).stream().findFirst();
        if (post.isPresent()) return post;
        record Signal(UUID ref, UUID spotId, UUID actorId, Instant created) {}
        List<Signal> signals = jdbc.query("""
                SELECT ref, spot_id, actor_id, effective_created_at FROM spot_signal
                WHERE group_ref = ? AND state = 'ACTIVE' AND expires_at > ? AND moderation_hidden_at IS NULL
                ORDER BY ref LIMIT 500 FOR SHARE
                """, (row, index) -> new Signal(row.getObject("ref", UUID.class), row.getObject("spot_id", UUID.class),
                        row.getObject("actor_id", UUID.class), row.getTimestamp("effective_created_at").toInstant()),
                ref, at);
        if (signals.isEmpty()) return Optional.empty();
        return Optional.of(new Reportable(ref, ItemKind.SUMMARY, signals.getFirst().spotId(),
                signals.stream().map(Signal::created).min(Instant::compareTo).orElseThrow(),
                signals.stream().map(Signal::actorId).distinct().toList(),
                signals.stream().map(Signal::ref).toList()));
    }

    @Override public StoredReport insertReport(UUID reporterId, UUID requestId, Reportable item, String reason,
            Instant now) {
        requireTransaction();
        String column = switch (reason) {
            case "false_alarm", "abuse", "spam", "personal_data", "unsafe" -> reason;
            default -> throw new IllegalArgumentException("Invalid report reason");
        };
        Timestamp window = Timestamp.from(item.windowStart());
        var stored = jdbc.queryForObject("""
                INSERT INTO spot_report (reporter_id, request_id, item_ref, item_kind, window_start, reason,
                    created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz + INTERVAL '7 days')
                RETURNING created_at, expires_at, review_sequence
                """, (row, index) -> new Object[] {row.getTimestamp("created_at").toInstant(),
                        row.getTimestamp("expires_at").toInstant(), row.getLong("review_sequence")},
                reporterId, requestId, item.ref(), item.kind().name(), window, reason, Timestamp.from(now),
                Timestamp.from(now));
        Timestamp created = Timestamp.from((Instant) stored[0]);
        jdbc.update("""
                INSERT INTO spot_report_group (item_ref, window_start, spot_id, item_kind, %1$s, latest,
                    latest_sequence, expires_at, open_since)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?::timestamptz + INTERVAL '30 days', ?)
                ON CONFLICT (item_ref, window_start) DO UPDATE SET %1$s = spot_report_group.%1$s + 1,
                    -- A report on a decided group reopens it (ADR 0075).
                    open_since = CASE WHEN spot_report_group.closed_through IS NOT NULL
                            AND spot_report_group.latest_sequence <= spot_report_group.closed_through
                        THEN excluded.open_since ELSE spot_report_group.open_since END,
                    latest = GREATEST(spot_report_group.latest, excluded.latest),
                    latest_sequence = GREATEST(spot_report_group.latest_sequence, excluded.latest_sequence),
                    expires_at = GREATEST(spot_report_group.latest, excluded.latest) + INTERVAL '30 days'
                """.formatted(column), item.ref(), window, item.spotId(), item.kind().name(), created,
                stored[2], created, created);
        jdbc.update("""
                INSERT INTO spot_report_evidence (ref, expires_at)
                SELECT evidence, ?::timestamptz + INTERVAL '%s' FROM unnest(?::uuid[]) AS evidence
                ORDER BY evidence
                -- A new report on evidence ruled not upheld blocks highlights again (ADR 0075). Sorted
                -- inserts keep row-lock order stable across concurrent reports.
                ON CONFLICT (ref) DO UPDATE SET not_upheld_at = NULL
                    WHERE spot_report_evidence.not_upheld_at IS NOT NULL
                """.formatted(EVIDENCE_LIFE), created, (Object) item.evidence().toArray(UUID[]::new));
        return new StoredReport(item.ref(), reason, (Instant) stored[0], (Instant) stored[1]);
    }

    @Override public Optional<BlockablePost> blockablePost(UUID ref, Instant now) {
        return jdbc.query("""
                SELECT actor_id, spot_id, room_day, alias FROM spot_post
                WHERE ref = ? AND state = 'ACTIVE' AND expires_at > ? AND moderation_hidden_at IS NULL
                """, (row, index) -> new BlockablePost(row.getObject("actor_id", UUID.class),
                        row.getObject("spot_id", UUID.class), row.getDate("room_day").toLocalDate(),
                        row.getString("alias")), ref, Timestamp.from(now)).stream().findFirst();
    }

    @Override public void hideAlias(UUID blockerId, UUID spotId, LocalDate roomDay, String alias, Instant now) {
        jdbc.update("""
                INSERT INTO spot_hidden_alias (blocker_id, spot_id, room_day, alias, created_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, blockerId, spotId, Date.valueOf(roomDay), alias, Timestamp.from(now));
    }
}
