package com.routiqo.core.moderation.infrastructure;

import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded physical cleanup; separately switched from grant administration. */
public final class JdbcTrafficGrantCleanup {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public JdbcTrafficGrantCleanup(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager); this.transaction.setTimeout(8);
    }
    public int cleanup(int batch) {
        if (batch < 1 || batch > 100) throw new IllegalArgumentException("Invalid cleanup batch");
        return transaction.execute(status -> {
            Timestamp now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
            int actions = jdbc.update("""
                    DELETE FROM traffic_grant_action_audit_v3 WHERE (administrator_id, request_id) IN
                    (SELECT administrator_id, request_id FROM traffic_grant_action_audit_v3
                     WHERE expires_at <= ? ORDER BY expires_at, administrator_id, request_id
                     LIMIT ? FOR UPDATE SKIP LOCKED)
                    """, now, batch);
            int reads = jdbc.update("""
                    DELETE FROM traffic_grant_read_audit_v3 WHERE id IN
                    (SELECT id FROM traffic_grant_read_audit_v3 WHERE expires_at <= ?
                     ORDER BY expires_at, id LIMIT ? FOR UPDATE SKIP LOCKED)
                    """, now, batch);
            // The FK check on a grant delete may lock its account. Acquire accounts
            // first, as issue/revoke do, so maintenance cannot invert that order.
            var owners = jdbc.query("""
                    SELECT DISTINCT operator_id FROM moderation_operator_grant
                    WHERE permission IN ('traffic_review', 'traffic_suppress') AND expires_at <= ?
                    ORDER BY operator_id LIMIT ?
                    """, (rs, n) -> rs.getObject(1, java.util.UUID.class), now, batch);
            int grants = 0;
            for (var owner : owners) {
                var locked = jdbc.query("SELECT id FROM routiqo_account WHERE id = ? FOR UPDATE SKIP LOCKED",
                        (rs, n) -> rs.getObject(1, java.util.UUID.class), owner);
                if (!locked.isEmpty()) grants += jdbc.update("""
                        DELETE FROM moderation_operator_grant
                        WHERE operator_id = ? AND permission IN ('traffic_review', 'traffic_suppress') AND expires_at <= ?
                        """, owner, now);
            }
            return actions + reads + grants;
        });
    }
}
