package com.routiqo.core.spot.infrastructure;

import com.routiqo.core.spot.application.SpotReportStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** PostgreSQL participant for Spot reports; report methods join the reporter's account transaction. */
final class JdbcSpotReportStore implements SpotReportStore {
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

    @Override public boolean reported(UUID reporterId, UUID itemRef) {
        requireTransaction();
        return !jdbc.queryForList("SELECT 1 FROM spot_report WHERE reporter_id = ? AND item_ref = ?",
                Integer.class, reporterId, itemRef).isEmpty();
    }

    @Override public int countReports(UUID reporterId, Instant since) {
        requireTransaction();
        return jdbc.queryForObject("SELECT count(*) FROM spot_report WHERE reporter_id = ? AND created_at > ?",
                Integer.class, reporterId, Timestamp.from(since));
    }

    @Override public Optional<Reportable> reportable(UUID ref, Instant now) {
        requireTransaction();
        Timestamp at = Timestamp.from(now);
        Optional<Reportable> post = jdbc.query("""
                SELECT spot_id, actor_id FROM spot_post WHERE ref = ? AND state = 'ACTIVE' AND expires_at > ?
                """, (row, index) -> new Reportable(ref, ItemKind.POST, row.getObject("spot_id", UUID.class),
                        List.of(row.getObject("actor_id", UUID.class))), ref, at).stream().findFirst();
        if (post.isPresent()) return post;
        List<UUID[]> signals = jdbc.query("""
                SELECT spot_id, actor_id FROM spot_signal
                WHERE group_ref = ? AND state = 'ACTIVE' AND expires_at > ? LIMIT 500
                """, (row, index) -> new UUID[] {row.getObject("spot_id", UUID.class),
                        row.getObject("actor_id", UUID.class)}, ref, at);
        if (signals.isEmpty()) return Optional.empty();
        return Optional.of(new Reportable(ref, ItemKind.SUMMARY, signals.getFirst()[0],
                signals.stream().map(pair -> pair[1]).distinct().toList()));
    }

    @Override public StoredReport insertReport(UUID reporterId, UUID requestId, Reportable item, String reason,
            Instant now) {
        requireTransaction();
        String column = switch (reason) {
            case "false_alarm", "abuse", "spam", "personal_data", "unsafe" -> reason;
            default -> throw new IllegalArgumentException("Invalid report reason");
        };
        var stored = jdbc.queryForObject("""
                INSERT INTO spot_report (reporter_id, request_id, item_ref, item_kind, reason, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::timestamptz + INTERVAL '7 days')
                RETURNING created_at, expires_at, review_sequence
                """, (row, index) -> new Object[] {row.getTimestamp("created_at").toInstant(),
                        row.getTimestamp("expires_at").toInstant(), row.getLong("review_sequence")},
                reporterId, requestId, item.ref(), item.kind().name(), reason, Timestamp.from(now),
                Timestamp.from(now));
        Instant created = (Instant) stored[0];
        jdbc.update("""
                INSERT INTO spot_report_group (item_ref, spot_id, item_kind, %1$s, latest, latest_sequence, expires_at)
                VALUES (?, ?, ?, 1, ?, ?, ?::timestamptz + INTERVAL '30 days')
                ON CONFLICT (item_ref) DO UPDATE SET %1$s = spot_report_group.%1$s + 1,
                    latest = GREATEST(spot_report_group.latest, excluded.latest),
                    latest_sequence = GREATEST(spot_report_group.latest_sequence, excluded.latest_sequence),
                    expires_at = GREATEST(spot_report_group.latest, excluded.latest) + INTERVAL '30 days'
                """.formatted(column), item.ref(), item.spotId(), item.kind().name(), Timestamp.from(created),
                stored[2], Timestamp.from(created));
        return new StoredReport(item.ref(), reason, created, (Instant) stored[1]);
    }

    @Override public Optional<UUID> postAuthor(UUID ref) {
        return jdbc.queryForList("SELECT actor_id FROM spot_post WHERE ref = ?", UUID.class, ref)
                .stream().findFirst();
    }
}
