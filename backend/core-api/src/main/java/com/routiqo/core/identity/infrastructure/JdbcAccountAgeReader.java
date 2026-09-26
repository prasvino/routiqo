package com.routiqo.core.identity.infrastructure;

import com.routiqo.core.identity.application.AccountAgeReader;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class JdbcAccountAgeReader implements AccountAgeReader {
    private final JdbcTemplate jdbc;

    JdbcAccountAgeReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<Instant> createdAt(UUID accountId) {
        if (accountId == null) throw new IllegalArgumentException("Account required");
        return jdbc.query("SELECT created_at FROM routiqo_account WHERE id = ? AND enabled = TRUE",
                (row, index) -> row.getTimestamp("created_at").toInstant(), accountId)
                .stream().findFirst();
    }
}
