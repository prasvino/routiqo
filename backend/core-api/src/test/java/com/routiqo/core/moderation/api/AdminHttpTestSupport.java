package com.routiqo.core.moderation.api;

import com.jayway.jsonpath.JsonPath;
import com.routiqo.core.identity.application.GoogleIdentityVerifier;
import com.routiqo.core.moderation.infrastructure.AdminSessionService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared admin HTTP harness: one browser (cookie jar and CSRF token) per signed-in operator. */
abstract class AdminHttpTestSupport {
    static final String ADMIN_ORIGIN = "http://localhost:3001";
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("ROUTIQO_AUTH_RATE_SECRET", () -> "admin-support-test-rate-secret-32-chars");
    }
    /** The test token is "nonce|subject", so each test chooses who signs in. */
    @TestConfiguration static class FakeIdentity {
        @Bean @Primary AdminSessionService fakeAdminSessionService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
            return new AdminSessionService(jdbc, manager, (token, nonce) -> {
                if (!token.startsWith(nonce + "|")) throw new SecurityException("Invalid test credential");
                return new GoogleIdentityVerifier.Identity("google", token.substring(nonce.length() + 1));
            });
        }
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;

    final class Browser {
        final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        final String csrf;
        UUID account;
        Browser() throws Exception {
            csrf = JsonPath.read(send("GET", "auth/csrf", "").body(), "$.token");
        }
        HttpResponse<String> send(String method, String path, String body) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/admin/" + path));
            if (method.equals("POST")) builder.header("Origin", ADMIN_ORIGIN).header("X-XSRF-TOKEN", csrf)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            else builder.GET();
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, ""); }
        HttpResponse<String> post(String path, String body) throws Exception { return send("POST", path, body); }
    }

    UUID account(String subject) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)", id, subject);
        return id;
    }

    void grant(UUID operator, String permission, Duration remaining) {
        Instant expires = Instant.now().plus(remaining);
        Instant issued = remaining.isNegative() ? expires.minusSeconds(1800) : Instant.now().minusSeconds(10);
        jdbc.update("""
                INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (operator_id, permission) DO UPDATE SET issued_at = EXCLUDED.issued_at,
                    expires_at = EXCLUDED.expires_at
                """, operator, permission, Timestamp.from(issued), Timestamp.from(expires));
    }

    /** A new account holding the given permissions for an hour, signed in through its own browser. */
    Browser operator(String... permissions) throws Exception {
        String subject = "operator-" + UUID.randomUUID();
        UUID id = account(subject);
        for (String permission : permissions) grant(id, permission, Duration.ofHours(1));
        var browser = new Browser();
        var challenge = browser.post("auth/google/challenge", "{}");
        assertThat(challenge.statusCode()).isEqualTo(200);
        String challengeId = JsonPath.read(challenge.body(), "$.id");
        String nonce = JsonPath.read(challenge.body(), "$.nonce");
        var exchange = browser.post("auth/google/exchange",
                "{\"challengeId\":\"" + challengeId + "\",\"idToken\":\"" + nonce + "|" + subject + "\"}");
        assertThat(exchange.statusCode()).isEqualTo(200);
        browser.account = id;
        return browser;
    }
}
