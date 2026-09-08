package com.routiqo.core.identity.infrastructure;

import com.routiqo.core.identity.application.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcSessionStore implements SessionStore {
    private final JdbcTemplate jdbc;
    private final GoogleAccountStore accounts;
    private final TransactionTemplate transaction;
    public JdbcSessionStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc); this.accounts = new JdbcGoogleAccountStore(jdbc);
        this.transaction = new TransactionTemplate(manager); this.transaction.setTimeout(5);
    }
    @Override public void createChallenge(UUID id, String nonce, String bindingHash, Instant now, Instant expiresAt) {
        jdbc.update("INSERT INTO login_challenge (id, nonce, binding_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                id, nonce, bindingHash, Timestamp.from(now), Timestamp.from(expiresAt));
    }
    private String nonce(UUID id, String bindingHash, Instant now, boolean lock) {
        var rows = jdbc.query("""
            SELECT nonce FROM login_challenge
            WHERE id = ? AND binding_hash = ? AND consumed_at IS NULL AND expires_at > ? AND created_at <= ?
            """ + (lock ? " FOR UPDATE" : ""), (row, index) -> row.getString("nonce"),
                id, bindingHash, Timestamp.from(now), Timestamp.from(now));
        if (rows.isEmpty()) throw denied();
        return rows.getFirst();
    }
    @Override public String challengeNonce(UUID id, String bindingHash, Instant now) { return nonce(id, bindingHash, now, false); }
    @Override public UUID exchange(UUID id, String bindingHash, GoogleIdentityVerifier.Identity identity,
                                   String tokenHash, Instant now, Instant expiresAt) {
        return Objects.requireNonNull(transaction.execute(status -> {
            nonce(id, bindingHash, now, true);
            UUID account = accounts.resolve(identity);
            jdbc.update("UPDATE login_challenge SET consumed_at = ? WHERE id = ?", Timestamp.from(now), id);
            jdbc.update("INSERT INTO auth_session (token_hash, account_id, created_at, expires_at, authenticated_at) VALUES (?, ?, ?, ?, ?)",
                    tokenHash, account, Timestamp.from(now), Timestamp.from(expiresAt), Timestamp.from(now));
            return account;
        }));
    }
    @Override public UUID authenticate(String tokenHash, Instant now) {
        var rows = jdbc.query("""
            SELECT s.account_id FROM auth_session s JOIN routiqo_account a ON a.id = s.account_id
            WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > ? AND s.created_at <= ? AND a.enabled = TRUE
            """, (row, index) -> row.getObject("account_id", UUID.class), tokenHash, Timestamp.from(now), Timestamp.from(now));
        if (rows.isEmpty()) throw denied();
        return rows.getFirst();
    }
    @Override public void revoke(String tokenHash, Instant now) {
        transaction.executeWithoutResult(status -> {
            if (!lockAccount(tokenHash)) return;
            jdbc.update("""
                UPDATE auth_session SET revoked_at = GREATEST(created_at, ?)
                WHERE revoked_at IS NULL AND (account_id, authenticated_at) =
                    (SELECT account_id, authenticated_at FROM auth_session WHERE token_hash = ?)
                """, Timestamp.from(now), tokenHash);
        });
    }
    @Override public Renewal renew(String tokenHash, String replacementHash, Instant now) {
        return Objects.requireNonNull(transaction.execute(status -> {
            if (!lockAccount(tokenHash)) throw denied();
            var rows = jdbc.query("""
                SELECT s.account_id, s.expires_at, s.authenticated_at
                FROM auth_session s JOIN routiqo_account a ON a.id = s.account_id
                WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > ?
                  AND s.created_at <= ? AND a.enabled = TRUE
                FOR UPDATE OF s, a
                """, (row, index) -> new StoredSession(row.getObject("account_id", UUID.class),
                    row.getTimestamp("expires_at").toInstant(), row.getTimestamp("authenticated_at").toInstant()),
                    tokenHash, Timestamp.from(now), Timestamp.from(now));
            if (rows.isEmpty()) throw denied();
            var current = rows.getFirst();
            Instant absoluteExpiry = current.authenticatedAt().plusSeconds(43200);
            if (!absoluteExpiry.isAfter(now)) throw denied();
            if (current.expiresAt().isAfter(now.plusSeconds(300)))
                return new Renewal(current.accountId(), current.expiresAt(), false);
            Instant expires = now.plusSeconds(900);
            if (expires.isAfter(absoluteExpiry)) expires = absoluteExpiry;
            jdbc.update("UPDATE auth_session SET revoked_at = ? WHERE token_hash = ?", Timestamp.from(now), tokenHash);
            jdbc.update("""
                INSERT INTO auth_session (token_hash, account_id, created_at, expires_at, authenticated_at)
                VALUES (?, ?, ?, ?, ?)
                """, replacementHash, current.accountId(), Timestamp.from(now), Timestamp.from(expires),
                    Timestamp.from(current.authenticatedAt()));
            return new Renewal(current.accountId(), expires, true);
        }));
    }
    private record StoredSession(UUID accountId, Instant expiresAt, Instant authenticatedAt) {}
    @Override public void deleteAccount(String tokenHash, Instant now) {
        transaction.executeWithoutResult(status -> {
            if (!lockAccount(tokenHash)) throw denied();
            var rows = jdbc.query("""
                SELECT s.account_id, s.authenticated_at FROM auth_session s
                JOIN routiqo_account a ON a.id = s.account_id
                WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > ?
                  AND s.created_at <= ? AND a.enabled = TRUE FOR UPDATE OF s, a
                """, (row, index) -> new StoredSession(row.getObject("account_id", UUID.class), now,
                    row.getTimestamp("authenticated_at").toInstant()), tokenHash, Timestamp.from(now), Timestamp.from(now));
            if (rows.isEmpty()) throw denied();
            var current = rows.getFirst();
            if (!current.authenticatedAt().isAfter(now.minusSeconds(300))) throw new RecentAuthenticationRequired();
            jdbc.update("DELETE FROM routiqo_account WHERE id = ?", current.accountId());
        });
    }
    private boolean lockAccount(String tokenHash) {
        // Always account before session: deletion cascades across every session for this account.
        // Taking a session first could deadlock with another session's concurrent renewal.
        var rows = jdbc.query("""
            SELECT id FROM routiqo_account WHERE id =
                (SELECT account_id FROM auth_session WHERE token_hash = ?) FOR UPDATE
            """, (row, index) -> row.getObject("id", UUID.class), tokenHash);
        return !rows.isEmpty();
    }
    private static SecurityException denied() { return new SecurityException("Authentication could not be completed"); }
}

