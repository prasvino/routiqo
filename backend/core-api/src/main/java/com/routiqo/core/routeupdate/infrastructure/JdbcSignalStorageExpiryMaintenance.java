package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.routeupdate.application.SignalStorageExpiryMaintenance;
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

public final class JdbcSignalStorageExpiryMaintenance implements SignalStorageExpiryMaintenance {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public JdbcSignalStorageExpiryMaintenance(
            JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setTimeout(5);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public int purgeExpiredGrants(int limit) {
        return purge("signal_command_grant", "command_id", "expires_at", limit);
    }

    @Override
    public int purgeExpiredReceipts(int limit) {
        return purge("quick_signal_receipt", "command_id", "retain_until", limit);
    }

    private int purge(String table, String identity, String expiry, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Signal cleanup limit is invalid");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Signal cleanup cannot join a transaction");
        }
        try {
            return transaction.execute(status -> {
                try {
                    Instant cutoff = clock.instant().truncatedTo(ChronoUnit.MICROS);
                    String select = "SELECT actor_id, " + identity + ", " + expiry + " FROM "
                            + table + " WHERE " + expiry + " <= ? ORDER BY " + expiry
                            + ", actor_id LIMIT ? FOR UPDATE SKIP LOCKED";
                    List<Candidate> rows = jdbc.query(select,
                            (row, number) -> new Candidate(
                                    row.getObject("actor_id", UUID.class),
                                    row.getObject(identity, UUID.class),
                                    row.getTimestamp(expiry).toInstant()),
                            Timestamp.from(cutoff), limit);
                    int deleted = 0;
                    String delete = "DELETE FROM " + table + " WHERE actor_id = ? AND "
                            + identity + " = ? AND " + expiry + " = ? AND " + expiry + " <= ?";
                    for (Candidate row : rows) {
                        deleted += jdbc.update(delete, row.actorId(), row.identity(),
                                Timestamp.from(row.expiry()), Timestamp.from(cutoff));
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

    private record Candidate(UUID actorId, UUID identity, Instant expiry) {
        @Override public String toString() { return "ExpiredSignalStorage[private]"; }
    }
}
