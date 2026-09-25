package com.routiqo.core.moderation.infrastructure;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Canonical-summary review only. Reporters and candidate rows never leave this boundary. */
public final class JdbcTrafficReview {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);

    public JdbcTrafficReview(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager); this.transaction.setTimeout(8);
        this.clock = clock;
    }

    public record Item(UUID ref, Map<String, Integer> reasonCounts, String evidenceStatus,
            String areaLabel, String trafficValue, String observationPeriod, Instant expiresAt) {}
    public record Queue(List<Item> items, String nextCursor) {}
    private record Row(UUID ref, Item item, boolean urgent, Instant latest) {}
    private record Cursor(boolean urgent, Instant latest, UUID ref) {}

    public Queue queue(UUID operator, String rawCursor, int limit, Runnable sessionRecheck) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid page size");
        Cursor cursor = decode(rawCursor);
        return transaction.execute(status -> {
            sessionRecheck.run();
            Instant now = clock.instant();
            grant(operator, false, now);
            // Reporter-free group counts (ADR 0064): groups with an UNSAFE report come first, then
            // newest. Closed groups (suppressed, or dismissed/closed through their latest report) stay
            // in the page scan so cursors advance, but are not returned.
            List<Row> rows = jdbc.query("""
                    WITH candidates AS MATERIALIZED (
                      SELECT g.ref, g.unsafe > 0 AS urgent, g.latest FROM community_traffic_report_group_v3 g
                      WHERE g.expires_at > ?
                        AND (?::boolean IS NULL OR (g.unsafe > 0, g.latest, g.ref) < (?, ?, ?))
                      ORDER BY g.unsafe > 0 DESC, g.latest DESC, g.ref DESC LIMIT ?
                    )
                    SELECT c.ref, c.urgent, c.latest, g.latest_sequence, g.inaccurate, g.unsafe, g.spam,
                      d.action, d.closed_through,
                      p.area_label, p.traffic_value, p.window_start, p.expires_at
                    FROM candidates c
                    JOIN community_traffic_report_group_v3 g ON g.ref = c.ref
                    LEFT JOIN community_traffic_review_disposition_v3 d ON d.ref = c.ref
                    LEFT JOIN community_traffic_projection_v3 p ON p.ref = c.ref
                      AND p.expires_at > ? AND p.suppressed_at IS NULL
                    ORDER BY c.urgent DESC, c.latest DESC, c.ref DESC
                    """, (rs, n) -> {
                        UUID ref = rs.getObject(1, UUID.class);
                        boolean urgent = rs.getBoolean(2);
                        Instant latest = rs.getTimestamp(3).toInstant();
                        long sequence = rs.getLong(4);
                        String action = rs.getString(8);
                        if ("SUPPRESS".equals(action) || action != null && sequence <= rs.getLong(9))
                            return new Row(ref, null, urgent, latest);
                        Instant start = rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant();
                        var item = new Item(ref, Map.of("INACCURATE", rs.getInt(5), "UNSAFE", rs.getInt(6), "SPAM", rs.getInt(7)),
                                start == null ? "EVIDENCE_UNAVAILABLE" : "AVAILABLE",
                                start == null ? null : rs.getString(10), start == null ? null : rs.getString(11),
                                start == null ? null : PERIOD.format(start) + "–" + PERIOD.format(start.plusSeconds(300)) + " UTC",
                                start == null ? null : rs.getTimestamp(13).toInstant());
                        return new Row(ref, item, urgent, latest);
                    }, Timestamp.from(now), cursor == null ? null : cursor.urgent(),
                    cursor == null ? null : cursor.urgent(),
                    cursor == null ? null : Timestamp.from(cursor.latest()), cursor == null ? null : cursor.ref(),
                    limit + 1, Timestamp.from(now));
            int examined = Math.min(rows.size(), limit);
            var items = rows.subList(0, examined).stream().map(Row::item)
                    .filter(java.util.Objects::nonNull).toList();
            sessionRecheck.run();
            grant(operator, false, clock.instant());
            jdbc.update("""
                    INSERT INTO community_traffic_moderator_read_audit_v3
                    (id, operator_id, occurred_at, expires_at, item_count) VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), operator, Timestamp.from(now), Timestamp.from(now.plusSeconds(720L * 3600)), items.size());
            String next = rows.size() > limit ? encode(rows.get(examined - 1)) : null;
            return new Queue(items, next);
        });
    }

    public void decide(UUID operator, UUID requestId, UUID ref, String action, String reason,
            Runnable sessionRecheck) {
        if (operator == null || requestId == null || ref == null || requestId.equals(new UUID(0, 0))
                || !List.of("DISMISS", "SUPPRESS").contains(action)
                || !List.of("INACCURATE", "UNSAFE", "SPAM", "POLICY").contains(reason))
            throw new IllegalArgumentException("Invalid review request");
        transaction.executeWithoutResult(status -> {
            Instant now = clock.instant();
            sessionRecheck.run();
            grantAvailable(operator, action.equals("SUPPRESS"));
            var prior = jdbc.query("""
                    SELECT ref, action, reason FROM community_traffic_review_action_audit_v3
                    WHERE operator_id = ? AND request_id = ?
                    """, (rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, operator, requestId);
            if (!prior.isEmpty()) {
                String[] previous = prior.getFirst();
                if (previous[0].equals(ref.toString()) && previous[1].equals(action) && previous[2].equals(reason)) {
                    grant(operator, action.equals("SUPPRESS"), clock.instant());
                    return;
                }
                throw new Conflict();
            }
            var projection = jdbc.query("""
                    SELECT ref FROM community_traffic_projection_v3
                    WHERE ref = ? AND expires_at > ? AND suppressed_at IS NULL FOR UPDATE
                    """, (rs, n) -> rs.getObject(1, UUID.class), ref, Timestamp.from(clock.instant()));
            if (projection.isEmpty()) throw new Missing();
            now = clock.instant();
            sessionRecheck.run();
            var currentProjection = jdbc.query("""
                    SELECT TRUE FROM community_traffic_projection_v3
                    WHERE ref = ? AND expires_at > ? AND suppressed_at IS NULL
                    """, (rs, n) -> true, ref, Timestamp.from(now));
            if (currentProjection.isEmpty()) throw new Missing();
            // Lock a live report before its group: DELETE holds the row first and its
            // reconciliation trigger then locks the group. This order avoids a deadlock.
            var lockedReport = jdbc.query("""
                    SELECT review_sequence FROM community_traffic_report_v3
                    WHERE ref = ? AND expires_at > ?
                    ORDER BY review_sequence DESC LIMIT 1 FOR SHARE
                    """, (rs, n) -> rs.getLong(1), ref, Timestamp.from(clock.instant()));
            if (lockedReport.isEmpty()) throw new Missing();
            var group = jdbc.query("SELECT ref FROM community_traffic_report_group_v3 WHERE ref = ? FOR UPDATE",
                    (rs, n) -> rs.getObject(1, UUID.class), ref);
            if (group.isEmpty()) throw new Missing();
            now = clock.instant();
            sessionRecheck.run();
            grant(operator, action.equals("SUPPRESS"), now);
            // The queue and decisions share one notion of "open": the group's latest report sequence.
            var latest = jdbc.query("""
                    SELECT latest_sequence FROM community_traffic_report_group_v3
                    WHERE ref = ? AND expires_at > ?
                    """, (rs, n) -> rs.getObject(1, Long.class),
                    ref, Timestamp.from(now));
            if (latest.isEmpty() || latest.getFirst() == null) throw new Missing();
            var disposition = jdbc.query("SELECT action, closed_through FROM community_traffic_review_disposition_v3 WHERE ref = ?",
                    (rs, n) -> new Object[]{rs.getString(1), rs.getLong(2)}, ref);
            if (!disposition.isEmpty() && ("SUPPRESS".equals(disposition.getFirst()[0])
                    || latest.getFirst() <= (Long) disposition.getFirst()[1])) throw new Missing();
            Instant expiry = now.plusSeconds(720L * 3600);
            if ("SUPPRESS".equals(action)) {
                if (jdbc.update("UPDATE community_traffic_projection_v3 SET suppressed_at = ? WHERE ref = ? AND suppressed_at IS NULL AND expires_at > ?",
                        Timestamp.from(now), ref, Timestamp.from(now)) != 1) throw new Missing();
                jdbc.update("""
                        INSERT INTO community_traffic_suppression_audit_v3
                        (operator_id, request_id, ref, reason, occurred_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, operator, requestId, ref, reason, Timestamp.from(now), Timestamp.from(expiry));
            }
            jdbc.update("""
                    INSERT INTO community_traffic_review_action_audit_v3
                    (operator_id, request_id, ref, action, reason, occurred_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, operator, requestId, ref, action, reason, Timestamp.from(now), Timestamp.from(expiry));
            jdbc.update("""
                    INSERT INTO community_traffic_review_disposition_v3
                    (ref, operator_id, request_id, action, reason, closed_through, occurred_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (ref) DO UPDATE SET operator_id = EXCLUDED.operator_id,
                      request_id = EXCLUDED.request_id, action = EXCLUDED.action, reason = EXCLUDED.reason,
                      closed_through = EXCLUDED.closed_through, occurred_at = EXCLUDED.occurred_at,
                      expires_at = EXCLUDED.expires_at
                    """, ref, operator, requestId, action, reason, latest.getFirst(),
                    Timestamp.from(now), Timestamp.from(expiry));
        });
    }

    private void grant(UUID operator, boolean suppress, Instant ignored) {
        var rows = jdbc.query("""
                SELECT g.issued_at, g.expires_at FROM moderation_operator_grant g
                JOIN routiqo_account a ON a.id = g.operator_id AND a.enabled = TRUE
                WHERE g.operator_id = ? AND g.permission IN (%s)
                FOR UPDATE OF g
                """.formatted(suppress ? "'traffic_suppress'" : "'traffic_review', 'traffic_suppress'"),
                (rs, n) -> new Instant[]{rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()}, operator);
        Instant now = databaseNow();
        if (rows.stream().noneMatch(row -> !row[0].isAfter(now) && row[1].isAfter(now))) throw new Missing();
    }
    private void grantAvailable(UUID operator, boolean suppress) {
        Instant now = databaseNow();
        var rows = jdbc.query("""
                SELECT TRUE FROM moderation_operator_grant g
                JOIN routiqo_account a ON a.id = g.operator_id AND a.enabled = TRUE
                WHERE g.operator_id = ? AND g.permission IN (%s)
                  AND g.issued_at <= ? AND g.expires_at > ?
                """.formatted(suppress ? "'traffic_suppress'" : "'traffic_review', 'traffic_suppress'"),
                (rs, n) -> true, operator, Timestamp.from(now), Timestamp.from(now));
        if (rows.isEmpty()) throw new Missing();
    }
    private Instant databaseNow() { return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant(); }
    private static String encode(Row row) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("2|" + row.urgent() + "|" + row.latest() + "|" + row.ref()).getBytes(StandardCharsets.US_ASCII));
    }
    private static Cursor decode(String raw) {
        if (raw == null) return null;
        if (raw.length() > 128 || !raw.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid cursor");
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.US_ASCII).split("\\|", -1);
            if (parts.length != 4 || !parts[0].equals("2") || !Set.of("true", "false").contains(parts[1]))
                throw new IllegalArgumentException();
            return new Cursor(Boolean.parseBoolean(parts[1]), Instant.parse(parts[2]), UUID.fromString(parts[3]));
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid cursor"); }
    }
    public static final class Missing extends RuntimeException {}
    public static final class Conflict extends RuntimeException {}
}
