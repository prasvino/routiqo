package com.routiqo.core.identity.infrastructure;

import com.routiqo.core.identity.application.AuthRateGate;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcAuthRateGate implements AuthRateGate {
    private final JdbcTemplate jdbc;
    private final byte[] secret;
    private final Clock clock;
    public JdbcAuthRateGate(JdbcTemplate jdbc, String secret, Clock clock) {
        if (secret == null || secret.length() < 32 || secret.length() > 256)
            throw new IllegalArgumentException("Auth rate-limit secret must contain 32 to 256 characters");
        this.jdbc = jdbc; this.secret = secret.getBytes(StandardCharsets.UTF_8); this.clock = clock;
    }
    @Override public boolean allow(String peerAddress, String category, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid request limit");
        String key;
        try {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            key = HexFormat.of().formatHex(mac.doFinal((category + "|" + peerAddress).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException error) { throw new IllegalStateException(error); }
        var rows = jdbc.query("""
            INSERT INTO auth_rate_bucket (key_hash, window_start, hits) VALUES (?, ?, 1)
            ON CONFLICT (key_hash, window_start) DO UPDATE SET hits = auth_rate_bucket.hits + 1
            WHERE auth_rate_bucket.hits < ? RETURNING hits
            """, (row, index) -> row.getInt("hits"), key, clock.instant().getEpochSecond() / 60, limit);
        return !rows.isEmpty();
    }
}
