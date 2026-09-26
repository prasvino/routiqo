package com.routiqo.core.moderation.infrastructure;

import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bounded purge of admin sign-in state and Spots grant records (ADR 0075): expired challenges and
 * sessions, 30-day Spots grant audit, and expired {@code spots_*} grants. Runs with the admin flag.
 */
public final class JdbcAdminCleanup {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public JdbcAdminCleanup(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager); this.transaction.setTimeout(8);
    }
    public int cleanup(int batch) {
        if (batch < 1 || batch > 100) throw new IllegalArgumentException("Invalid cleanup batch");
        return transaction.execute(status -> {
            Timestamp now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
            int challenges = jdbc.update("""
                    DELETE FROM admin_login_challenge WHERE id IN
                    (SELECT id FROM admin_login_challenge WHERE expires_at <= ?
                     ORDER BY expires_at, id LIMIT ? FOR UPDATE SKIP LOCKED)
                    """, now, batch);
            int sessions = jdbc.update("""
                    DELETE FROM admin_auth_session WHERE token_hash IN
                    (SELECT token_hash FROM admin_auth_session WHERE expires_at <= ?
                     ORDER BY expires_at, token_hash LIMIT ? FOR UPDATE SKIP LOCKED)
                    """, now, batch);
            int actions = jdbc.update("""
                    DELETE FROM spot_grant_action_audit WHERE (administrator_id, request_id) IN
                    (SELECT administrator_id, request_id FROM spot_grant_action_audit WHERE expires_at <= ?
                     ORDER BY expires_at, administrator_id, request_id LIMIT ? FOR UPDATE SKIP LOCKED)
                    """, now, batch);
            int reads = jdbc.update("""
                    DELETE FROM spot_grant_read_audit WHERE id IN
                    (SELECT id FROM spot_grant_read_audit WHERE expires_at <= ?
                     ORDER BY expires_at, id LIMIT ? FOR UPDATE SKIP LOCKED)
                    """, now, batch);
            // Accounts first, as issue and revoke lock them, so maintenance never inverts that order.
            var owners = jdbc.query("""
                    SELECT DISTINCT operator_id FROM moderation_operator_grant
                    WHERE permission LIKE 'spots\\_%' AND expires_at <= ? ORDER BY operator_id LIMIT ?
                    """, (rs, n) -> rs.getObject(1, UUID.class), now, batch);
            int grants = 0;
            for (var owner : owners) {
                var locked = jdbc.query("SELECT id FROM routiqo_account WHERE id = ? FOR UPDATE SKIP LOCKED",
                        (rs, n) -> rs.getObject(1, UUID.class), owner);
                if (!locked.isEmpty()) grants += jdbc.update("""
                        DELETE FROM moderation_operator_grant
                        WHERE operator_id = ? AND permission LIKE 'spots\\_%' AND expires_at <= ?
                        """, owner, now);
            }
            return challenges + sessions + actions + reads + grants;
        });
    }
}
