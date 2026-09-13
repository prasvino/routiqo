package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.routeupdate.application.LiveRouteContextExpiryMaintenance;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcLiveRouteContextExpiryMaintenance
        implements LiveRouteContextExpiryMaintenance {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public JdbcLiveRouteContextExpiryMaintenance(
            JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transaction.setTimeout(5);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public int purgeExpired(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Live route context cleanup limit is invalid");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Live route context cleanup cannot join a transaction");
        }
        try {
            return transaction.execute(status -> {
                try {
                    Instant cutoff = clock.instant().truncatedTo(ChronoUnit.MICROS);
                    List<Candidate> candidates = jdbc.query("""
                        SELECT actor_id, context_id, expires_at
                        FROM live_route_context
                        WHERE expires_at <= ?
                        ORDER BY expires_at, actor_id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """, (row, number) -> new Candidate(
                            row.getObject("actor_id", UUID.class),
                            row.getObject("context_id", UUID.class),
                            row.getTimestamp("expires_at").toInstant()),
                            Timestamp.from(cutoff), limit);
                    int deleted = 0;
                    for (Candidate candidate : candidates) {
                        deleted += jdbc.update("""
                            DELETE FROM live_route_context
                            WHERE actor_id = ? AND context_id = ? AND expires_at = ?
                              AND expires_at <= ?
                            """, candidate.actorId(), candidate.contextId(),
                                Timestamp.from(candidate.expiresAt()), Timestamp.from(cutoff));
                    }
                    return deleted;
                } catch (DataAccessException | TransactionException unavailable) {
                    throw new AccountWriteUnavailable();
                }
            });
        } catch (DataAccessException | TransactionException unavailable) {
            throw new AccountWriteUnavailable();
        }
    }

    private record Candidate(UUID actorId, UUID contextId, Instant expiresAt) {
        @Override
        public String toString() {
            return "ExpiredLiveRouteContext[private]";
        }
    }
}
