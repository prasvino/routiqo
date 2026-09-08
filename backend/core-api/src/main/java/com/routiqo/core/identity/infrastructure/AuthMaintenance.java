package com.routiqo.core.identity.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

public final class AuthMaintenance {
    private final JdbcTemplate jdbc;
    public AuthMaintenance(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Scheduled(fixedDelay = 300000, initialDelay = 300000)
    public void cleanExpired() {
        jdbc.update("""
            DELETE FROM login_challenge WHERE id IN (SELECT id FROM login_challenge
            WHERE expires_at < CURRENT_TIMESTAMP ORDER BY expires_at, id LIMIT 100 FOR UPDATE SKIP LOCKED)
            """);
        jdbc.update("""
            DELETE FROM auth_session WHERE token_hash IN (SELECT token_hash FROM auth_session
            WHERE authenticated_at < CURRENT_TIMESTAMP - INTERVAL '12 hours'
            ORDER BY authenticated_at, token_hash LIMIT 100 FOR UPDATE SKIP LOCKED)
            """);
        jdbc.update("""
            DELETE FROM auth_rate_bucket WHERE (key_hash, window_start) IN (SELECT key_hash, window_start FROM auth_rate_bucket
            WHERE window_start < CAST(FLOOR(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) / 60) AS BIGINT) - 2
            ORDER BY window_start LIMIT 100 FOR UPDATE SKIP LOCKED)
            """);
    }
}

