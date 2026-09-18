package com.routiqo.core.identity.infrastructure;

import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.identity.application.EnabledAccountPairAuthority;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL orders UUIDs before acquiring either account lock. */
public final class JdbcEnabledAccountPairAuthority implements EnabledAccountPairAuthority {
    private static final UUID NIL = new UUID(0, 0);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcEnabledAccountPairAuthority(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setTimeout(5);
    }

    @Override
    public <T> T withEnabledPair(UUID firstId, UUID secondId, Work<T> work) {
        if (firstId == null || secondId == null || NIL.equals(firstId) || NIL.equals(secondId)
                || firstId.equals(secondId) || work == null) throw denied();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Account pair authority cannot join a transaction");
        }
        try {
            return transaction.execute(status -> {
                try {
                    List<UUID> rows = jdbc.query("""
                        SELECT id FROM routiqo_account
                        WHERE id IN (?, ?) AND enabled = TRUE
                        ORDER BY id FOR UPDATE
                        """, (row, number) -> row.getObject("id", UUID.class), firstId, secondId);
                    if (rows.size() != 2 || !rows.contains(firstId) || !rows.contains(secondId)) {
                        throw denied();
                    }
                    return work.execute();
                } catch (DataAccessException | TransactionException unavailable) {
                    throw new AccountWriteUnavailable();
                }
            });
        } catch (DataAccessException | TransactionException unavailable) {
            throw new AccountWriteUnavailable();
        }
    }

    private static SecurityException denied() {
        return new SecurityException("Account pair authority denied");
    }
}
