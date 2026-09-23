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
            deleted += jdbc.update("""
                    DELETE FROM community_traffic_projection_v3 WHERE ref IN
                      (SELECT ref FROM community_traffic_projection_v3
                       WHERE expires_at + INTERVAL '24 hours' <= ?
                       ORDER BY expires_at, ref LIMIT ?)
                    """, Timestamp.from(now), limit);
            deleted += jdbc.update("""
                    DELETE FROM community_traffic_report_v3 WHERE (actor_id, request_id) IN
                      (SELECT actor_id, request_id FROM community_traffic_report_v3
                       WHERE expires_at <= ? ORDER BY expires_at, actor_id, request_id LIMIT ?)
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
