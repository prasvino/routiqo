package com.routiqo.core.identity.infrastructure;

import com.routiqo.core.identity.application.GoogleAccountStore;
import com.routiqo.core.identity.application.GoogleIdentityVerifier;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcGoogleAccountStore implements GoogleAccountStore {
    private final JdbcTemplate jdbc;
    public JdbcGoogleAccountStore(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }
    @Override public UUID resolve(GoogleIdentityVerifier.Identity identity) {
        if (identity == null || !"google".equals(identity.provider()) || identity.subject() == null
                || !identity.subject().matches("[A-Za-z0-9_-]{1,255}"))
            throw new SecurityException("Verified Google identity required");
        // A single PostgreSQL statement arbitrates concurrent first login across replicas.
        // Disabled accounts must never be recreated or silently re-enabled by a new login.
        var ids = jdbc.query("""
            INSERT INTO routiqo_account (id, google_subject) VALUES (?, ?)
            ON CONFLICT (google_subject) DO UPDATE SET google_subject = EXCLUDED.google_subject
            WHERE routiqo_account.enabled = TRUE
            RETURNING id
            """, (row, index) -> row.getObject("id", UUID.class), UUID.randomUUID(), identity.subject());
        if (ids.isEmpty()) throw new SecurityException("Account is unavailable");
        return ids.getFirst();
    }
}
