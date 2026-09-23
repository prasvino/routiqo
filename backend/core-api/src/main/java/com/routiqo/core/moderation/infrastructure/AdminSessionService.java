package com.routiqo.core.moderation.infrastructure;

import com.routiqo.core.identity.application.GoogleIdentityVerifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent admin challenge and session namespace. Exchange never provisions accounts. */
public final class AdminSessionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final GoogleIdentityVerifier verifier;
    private final SecureRandom random = new SecureRandom();

    public AdminSessionService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            GoogleIdentityVerifier verifier) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(5);
        this.verifier = verifier;
    }

    public record Challenge(UUID id, String nonce, String binding, Instant expiresAt) {
        @Override public String toString() { return "Challenge[redacted]"; }
    }
    public record Session(UUID accountId, String credential, Instant expiresAt) {
        @Override public String toString() { return "Session[redacted]"; }
    }

    public Challenge begin() {
        Instant now = databaseNow();
        var challenge = new Challenge(UUID.randomUUID(), secret(), secret(), now.plusSeconds(300));
        jdbc.update("INSERT INTO admin_login_challenge (id, nonce, binding_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                challenge.id(), challenge.nonce(), hash(challenge.binding()), Timestamp.from(now), Timestamp.from(challenge.expiresAt()));
        return challenge;
    }

    public Session exchange(UUID id, String binding, String idToken) {
        if (id == null || idToken == null || idToken.isBlank() || idToken.length() > 16384) throw denied();
        String bindingHash = hash(binding);
        Instant now = databaseNow();
        var nonce = jdbc.query("""
                SELECT nonce FROM admin_login_challenge WHERE id = ? AND binding_hash = ?
                  AND consumed_at IS NULL AND created_at <= ? AND expires_at > ?
                """, (row, n) -> row.getString(1), id, bindingHash, Timestamp.from(now), Timestamp.from(now));
        if (nonce.isEmpty()) throw denied();
        var identity = verifier.verify(idToken, nonce.getFirst()); // Network verification outside transaction.
        if (!"google".equals(identity.provider())) throw denied();
        String credential = secret();
        return transaction.execute(status -> {
            var locked = jdbc.query("""
                    SELECT nonce, created_at, expires_at FROM admin_login_challenge
                    WHERE id = ? AND binding_hash = ? AND consumed_at IS NULL FOR UPDATE
                    """, (row, n) -> new Object[]{row.getString(1), row.getTimestamp(2).toInstant(),
                            row.getTimestamp(3).toInstant()}, id, bindingHash);
            Instant at = databaseNow();
            Instant expires = at.plusSeconds(900);
            if (locked.isEmpty() || !locked.getFirst()[0].equals(nonce.getFirst())
                    || ((Instant) locked.getFirst()[1]).isAfter(at)
                    || !((Instant) locked.getFirst()[2]).isAfter(at)) throw denied();
            var ids = jdbc.query("""
                    SELECT a.id FROM routiqo_account a JOIN moderation_operator_grant g ON g.operator_id = a.id
                    WHERE a.google_subject = ? AND a.enabled = TRUE
                      AND g.permission IN ('traffic_review', 'traffic_suppress', 'traffic_grant_admin')
                      AND g.issued_at <= ? AND g.expires_at > ?
                    LIMIT 1
                    """, (row, n) -> row.getObject(1, UUID.class), identity.subject(), Timestamp.from(at), Timestamp.from(at));
            if (ids.isEmpty()) throw denied();
            jdbc.update("UPDATE admin_login_challenge SET consumed_at = ? WHERE id = ?", Timestamp.from(at), id);
            jdbc.update("INSERT INTO admin_auth_session (token_hash, account_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                    hash(credential), ids.getFirst(), Timestamp.from(at), Timestamp.from(expires));
            return new Session(ids.getFirst(), credential, expires);
        });
    }

    public Session authenticate(String credential) {
        Instant now = databaseNow();
        var rows = jdbc.query("""
                SELECT s.account_id, s.expires_at FROM admin_auth_session s
                JOIN routiqo_account a ON a.id = s.account_id AND a.enabled = TRUE
                JOIN moderation_operator_grant g ON g.operator_id = a.id
                WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.created_at <= ? AND s.expires_at > ?
                  AND g.permission IN ('traffic_review', 'traffic_suppress', 'traffic_grant_admin')
                  AND g.issued_at <= ? AND g.expires_at > ?
                LIMIT 1
                """, (row, n) -> new Session(row.getObject(1, UUID.class), null, row.getTimestamp(2).toInstant()),
                hash(credential), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        if (rows.isEmpty()) throw denied();
        return rows.getFirst();
    }

    public void revoke(String credential) {
        jdbc.update("UPDATE admin_auth_session SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL",
                Timestamp.from(databaseNow()), hash(credential));
    }

    /** Called inside the action transaction after its projection lock; blocks concurrent revocation. */
    public void assertCurrent(UUID account, String credential) {
        var rows = jdbc.query("""
                SELECT s.created_at, s.expires_at, s.revoked_at FROM admin_auth_session s
                JOIN routiqo_account a ON a.id = s.account_id AND a.enabled = TRUE
                WHERE s.token_hash = ? AND s.account_id = ?
                FOR SHARE OF s, a
                """, (rs, n) -> new Instant[]{rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()}, hash(credential), account);
        if (rows.isEmpty()) throw denied();
        Instant now = databaseNow();
        Instant[] session = rows.getFirst();
        if (session[2] != null || session[0].isAfter(now) || !session[1].isAfter(now)) throw denied();
    }

    private String secret() {
        byte[] value = new byte[32]; random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
    private Instant databaseNow() { return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant(); }
    private static String hash(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) throw denied();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static SecurityException denied() { return new SecurityException("Admin authentication unavailable"); }
}
