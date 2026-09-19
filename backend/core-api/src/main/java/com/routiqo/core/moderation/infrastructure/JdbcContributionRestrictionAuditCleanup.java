package com.routiqo.core.moderation.infrastructure;

import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.moderation.application.ContributionRestrictionAuditCleanup;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcContributionRestrictionAuditCleanup
        implements ContributionRestrictionAuditCleanup {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public JdbcContributionRestrictionAuditCleanup(JdbcTemplate jdbc,
            PlatformTransactionManager manager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transaction.setTimeout(5);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public int deleteExpired(int limit) {
        validate(limit);
        rejectAmbientTransaction();
        try {
            Integer deleted = transaction.execute(status -> {
                try {
                    return deleteAudits(limit);
                } catch (DataAccessException | TransactionException unavailable) {
                    throw new AccountWriteUnavailable();
                }
            });
            return deleted == null ? 0 : deleted;
        } catch (DataAccessException | TransactionException unavailable) {
            throw new AccountWriteUnavailable();
        }
    }

    @Override public int purgeExpiredDebits(int limit) {
        validate(limit);
        rejectAmbientTransaction();
        try {
            Integer deleted = transaction.execute(status -> {
                try {
                    return deleteDebits(limit);
                } catch (DataAccessException | TransactionException unavailable) {
                    throw new AccountWriteUnavailable();
                }
            });
            return deleted == null ? 0 : deleted;
        } catch (DataAccessException | TransactionException unavailable) {
            throw new AccountWriteUnavailable();
        }
    }

    private int deleteAudits(int limit) {
        Instant cutoff = now();
        var rows = jdbc.query("""
            SELECT operator_id, request_id, expires_at FROM moderation_contribution_audit
            WHERE expires_at <= ? ORDER BY expires_at, operator_id, request_id
            LIMIT ? FOR UPDATE SKIP LOCKED
            """, (row, number) -> new AuditKey(row.getObject(1, UUID.class),
                    row.getObject(2, UUID.class), row.getTimestamp(3).toInstant()),
                Timestamp.from(cutoff), limit);
        int count = 0;
        for (AuditKey row : rows) count += jdbc.update("""
            DELETE FROM moderation_contribution_audit
            WHERE operator_id = ? AND request_id = ? AND expires_at = ? AND expires_at <= ?
            """, row.operatorId(), row.requestId(), Timestamp.from(row.expiresAt()),
                Timestamp.from(cutoff));
        return count;
    }

    private int deleteDebits(int limit) {
        Instant cutoff = now().minus(Duration.ofHours(1));
        var rows = jdbc.query("""
            SELECT operator_id, slot, used_at FROM moderation_restriction_action_slot
            WHERE used_at <= ? ORDER BY used_at, operator_id, slot
            LIMIT ? FOR UPDATE SKIP LOCKED
            """, (row, number) -> new DebitKey(row.getObject(1, UUID.class), row.getInt(2),
                    row.getTimestamp(3).toInstant()), Timestamp.from(cutoff), limit);
        int count = 0;
        for (DebitKey row : rows) count += jdbc.update("""
            DELETE FROM moderation_restriction_action_slot
            WHERE operator_id = ? AND slot = ? AND used_at = ? AND used_at <= ?
            """, row.operatorId(), row.slot(), Timestamp.from(row.usedAt()),
                Timestamp.from(cutoff));
        return count;
    }

    private Instant now() {
        try {
            Instant value = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (value.equals(Instant.MIN) || value.equals(Instant.MAX)) {
                throw new AccountWriteUnavailable();
            }
            return value;
        } catch (RuntimeException unavailable) {
            if (unavailable instanceof AccountWriteUnavailable mapped) throw mapped;
            throw new AccountWriteUnavailable();
        }
    }

    private static void validate(int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("Invalid cleanup limit");
    }

    private static void rejectAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Audit cleanup cannot join a transaction");
        }
    }

    private record AuditKey(UUID operatorId, UUID requestId, Instant expiresAt) {
        @Override public String toString() { return "ExpiredRestrictionAudit[private]"; }
    }
    private record DebitKey(UUID operatorId, int slot, Instant usedAt) {
        @Override public String toString() { return "ExpiredRestrictionDebit[private]"; }
    }
}
