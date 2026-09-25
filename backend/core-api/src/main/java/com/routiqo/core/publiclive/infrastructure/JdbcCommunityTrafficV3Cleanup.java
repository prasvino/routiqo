package com.routiqo.core.publiclive.infrastructure;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent retention path that remains runnable after serving/publication rollback. */
public final class JdbcCommunityTrafficV3Cleanup {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public JdbcCommunityTrafficV3Cleanup(JdbcTemplate jdbc, PlatformTransactionManager manager,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transaction.setTimeout(8);
        this.clock = Objects.requireNonNull(clock);
    }

    public int cleanup(int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("Invalid cleanup limit");
        return transaction.execute(status -> {
            var now = clock.instant();
            int deleted = jdbc.update("""
                    DELETE FROM community_traffic_candidate_v3 WHERE candidate_id IN
                      (SELECT candidate_id FROM community_traffic_candidate_v3
                       WHERE expires_at <= ? ORDER BY expires_at, candidate_id LIMIT ?)
                    """, Timestamp.from(now), limit);
            // Lock the projections being purged first (the moderator decision order is projection,
            // then report, then group), then close their still-open report groups as evidence
            // unavailable (ADR 0064). A suppression is never rewritten; a dismissal is replaced only
            // when a later report reopened it.
            var purged = jdbc.query("""
                    SELECT ref FROM community_traffic_projection_v3
                    WHERE expires_at + INTERVAL '24 hours' <= ?
                    ORDER BY expires_at, ref LIMIT ? FOR UPDATE
                    """, (row, n) -> row.getObject(1, java.util.UUID.class), Timestamp.from(now), limit);
            if (!purged.isEmpty()) {
                var refs = purged.toArray(new java.util.UUID[0]);
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement("""
                            INSERT INTO community_traffic_review_disposition_v3
                              (ref, operator_id, request_id, action, reason, closed_through, occurred_at, expires_at)
                            SELECT g.ref, NULL, NULL, 'CLOSED_EVIDENCE_UNAVAILABLE', NULL, g.latest_sequence, ?, ?
                            FROM community_traffic_report_group_v3 g WHERE g.ref = ANY(?)
                            ON CONFLICT (ref) DO UPDATE SET operator_id = NULL, request_id = NULL,
                              action = EXCLUDED.action, reason = NULL, closed_through = EXCLUDED.closed_through,
                              occurred_at = EXCLUDED.occurred_at, expires_at = EXCLUDED.expires_at
                            WHERE community_traffic_review_disposition_v3.action = 'DISMISS'
                              AND community_traffic_review_disposition_v3.closed_through < EXCLUDED.closed_through
                            """);
                    statement.setTimestamp(1, Timestamp.from(now));
                    statement.setTimestamp(2, Timestamp.from(now.plusSeconds(720L * 3600)));
                    statement.setArray(3, connection.createArrayOf("uuid", refs));
                    return statement;
                });
                deleted += jdbc.update(connection -> {
                    var statement = connection.prepareStatement(
                            "DELETE FROM community_traffic_projection_v3 WHERE ref = ANY(?)");
                    statement.setArray(1, connection.createArrayOf("uuid", refs));
                    return statement;
                });
            }
            // A retention purge keeps reporter-free group counts; only account deletion removes a
            // reporter's contribution (see the V29 trigger).
            jdbc.queryForObject("SELECT set_config('routiqo.report_retention_purge', 'on', true)", String.class);
            deleted += jdbc.update("""
                    DELETE FROM community_traffic_report_v3 WHERE (actor_id, request_id) IN
                      (SELECT actor_id, request_id FROM community_traffic_report_v3
                       WHERE expires_at <= ? ORDER BY expires_at, actor_id, request_id LIMIT ?)
                    """, Timestamp.from(now), limit);
            jdbc.queryForObject("SELECT set_config('routiqo.report_retention_purge', 'off', true)", String.class);
            deleted += jdbc.update("""
                    DELETE FROM community_traffic_report_group_v3 WHERE ref IN
                      (SELECT ref FROM community_traffic_report_group_v3
                       WHERE expires_at <= ? ORDER BY expires_at, ref LIMIT ?)
                    """, Timestamp.from(now), limit);
            deleted += jdbc.update("""
                    DELETE FROM community_traffic_review_disposition_v3 WHERE ref IN
                      (SELECT ref FROM community_traffic_review_disposition_v3
                       WHERE expires_at <= ? ORDER BY expires_at, ref LIMIT ?)
                    """, Timestamp.from(now), limit);
            deleted += jdbc.update("""
                    DELETE FROM community_traffic_review_action_audit_v3 WHERE (operator_id, request_id) IN
                      (SELECT operator_id, request_id FROM community_traffic_review_action_audit_v3
                       WHERE expires_at <= ? ORDER BY expires_at, operator_id, request_id LIMIT ?)
                    """, Timestamp.from(now), limit);
            deleted += jdbc.update("""
                    DELETE FROM community_traffic_moderator_read_audit_v3 WHERE id IN
                      (SELECT id FROM community_traffic_moderator_read_audit_v3
                       WHERE expires_at <= ? ORDER BY expires_at, id LIMIT ?)
                    """, Timestamp.from(now), limit);
            deleted += jdbc.update("""
                    DELETE FROM admin_login_challenge WHERE id IN
                      (SELECT id FROM admin_login_challenge
                       WHERE expires_at <= ? ORDER BY expires_at, id LIMIT ?)
                    """, Timestamp.from(now), limit);
            deleted += jdbc.update("""
                    DELETE FROM admin_auth_session WHERE token_hash IN
                      (SELECT token_hash FROM admin_auth_session
                       WHERE expires_at <= ? ORDER BY expires_at, token_hash LIMIT ?)
                    """, Timestamp.from(now), limit);
            deleted += jdbc.update("""
                    DELETE FROM community_traffic_suppression_audit_v3 WHERE (operator_id, request_id) IN
                      (SELECT operator_id, request_id FROM community_traffic_suppression_audit_v3
                       WHERE expires_at <= ? ORDER BY expires_at, operator_id, request_id LIMIT ?)
                    """, Timestamp.from(now), limit);
            deleted += jdbc.update("""
                    DELETE FROM community_traffic_daily_debit_v3 WHERE (actor_id, utc_day) IN
                      (SELECT actor_id, utc_day FROM community_traffic_daily_debit_v3
                       WHERE utc_day < (?::date - INTERVAL '2 days')
                       ORDER BY utc_day, actor_id LIMIT ?)
                    """, now.atZone(ZoneOffset.UTC).toLocalDate(), limit);
            return deleted;
        });
    }
}
