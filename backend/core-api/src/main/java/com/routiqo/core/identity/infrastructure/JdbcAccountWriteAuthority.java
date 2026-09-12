package com.routiqo.core.identity.infrastructure;

import com.routiqo.core.identity.application.AccountWriteAuthority;
import com.routiqo.core.identity.application.AccountWriteUnavailable;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcAccountWriteAuthority implements AccountWriteAuthority {
    private static final UUID NIL_ID = new UUID(0, 0);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcAccountWriteAuthority(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transaction.setTimeout(5);
    }

    @Override
    public <T> T withEnabledAccount(UUID actorId, Work<T> work) {
        if (actorId == null || NIL_ID.equals(actorId) || work == null) {
            throw denied();
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Account write authority cannot join a transaction");
        }
        try {
            return transaction.execute(status -> {
                try {
                    List<UUID> rows = jdbc.query("""
                        SELECT id FROM routiqo_account
                        WHERE id = ? AND enabled = TRUE
                        FOR UPDATE
                        """, (row, index) -> row.getObject("id", UUID.class), actorId);
                    if (rows.isEmpty()) {
                        throw denied();
                    }
                    return work.execute();
                } catch (DataAccessException | TransactionException unavailable) {
                    // Sanitize before TransactionTemplate attempts rollback and may log this exception.
                    throw new AccountWriteUnavailable();
                }
            });
        } catch (DataAccessException | TransactionException unavailable) {
            throw new AccountWriteUnavailable();
        }
    }

    private static SecurityException denied() {
        return new SecurityException("Account write authority denied");
    }
}
