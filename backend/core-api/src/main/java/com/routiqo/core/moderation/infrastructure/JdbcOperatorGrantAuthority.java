package com.routiqo.core.moderation.infrastructure;

import com.routiqo.core.moderation.application.OperatorGrantAuthority;
import com.routiqo.core.moderation.application.OperatorNotPermitted;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Grant check under the caller's transaction, compared with database time and held FOR SHARE. */
public final class JdbcOperatorGrantAuthority implements OperatorGrantAuthority {
    private final JdbcTemplate jdbc;
    public JdbcOperatorGrantAuthority(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void requireCurrent(UUID operator, String permission) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Grant checks run inside the action transaction");
        if (operator == null || permission == null || !permission.startsWith("spots_")) throw new OperatorNotPermitted();
        var current = jdbc.query("""
                SELECT g.issued_at <= clock_timestamp() AND g.expires_at > clock_timestamp()
                FROM moderation_operator_grant g JOIN routiqo_account a ON a.id = g.operator_id AND a.enabled = TRUE
                WHERE g.operator_id = ? AND g.permission = ?
                FOR SHARE OF g
                """, (row, index) -> row.getBoolean(1), operator, permission);
        if (current.isEmpty() || !current.getFirst()) throw new OperatorNotPermitted();
    }

}
