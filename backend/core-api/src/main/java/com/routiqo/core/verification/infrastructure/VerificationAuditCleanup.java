package com.routiqo.core.verification.infrastructure;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded, separately invoked retention cleanup. No default scheduler is installed. */
public final class VerificationAuditCleanup {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public VerificationAuditCleanup(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transaction.setTimeout(5);
        this.clock = Objects.requireNonNull(clock);
    }

    public int deleteExpiredBatch() {
        return transaction.execute(status -> jdbc.update("""
            DELETE FROM live_verification_audit WHERE (reviewer_id, request_id) IN (
                SELECT reviewer_id, request_id FROM live_verification_audit
                WHERE expires_at <= ? ORDER BY expires_at, reviewer_id, request_id
                LIMIT 100 FOR UPDATE SKIP LOCKED
            )
            """, Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS))));
    }
}
