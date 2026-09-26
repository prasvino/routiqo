package com.routiqo.core.moderation.api;

import com.jayway.jsonpath.JsonPath;
import com.routiqo.core.identity.application.GoogleIdentityVerifier;
import com.routiqo.core.moderation.infrastructure.AdminSessionService;
import com.routiqo.core.moderation.infrastructure.JdbcAdminCleanup;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0075: the admin base on its own flag, Spots permissions at sign-in, and renewable 8-hour sessions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=consumer-client.apps.googleusercontent.com",
    "ROUTIQO_ADMIN_GOOGLE_CLIENT_ID=admin-client.apps.googleusercontent.com",
    "ROUTIQO_WEB_ORIGIN=http://localhost:3000", "ROUTIQO_ADMIN_ORIGIN=http://localhost:3001",
    "ROUTIQO_AUTH_SECURE_COOKIES=false", "ROUTIQO_ADMIN_ENABLED=true"
})
@ActiveProfiles({"persistence", "google-auth", "web-auth"})
@Import(AdminAccessHttpTest.FakeIdentity.class)
class AdminAccessHttpTest {
    static final String ADMIN_ORIGIN = "http://localhost:3001";
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("ROUTIQO_AUTH_RATE_SECRET", () -> "admin-access-test-rate-secret-32-chars");
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
    @Autowired JdbcAdminCleanup cleanup;
    HttpClient client;
    String csrf;

    @BeforeEach void setup() throws Exception {
        jdbc.update("DELETE FROM auth_rate_bucket");
        jdbc.update("DELETE FROM admin_auth_session");
        client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var response = call("GET", "auth/csrf", "", false);
        csrf = JsonPath.read(response.body(), "$.token");
    }

    HttpResponse<String> call(String method, String path, String body, boolean protectedPost) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/admin/" + path));
        if (protectedPost) builder.header("Origin", ADMIN_ORIGIN).header("X-XSRF-TOKEN", csrf);
        if (method.equals("POST")) builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        else builder.GET();
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
                """, operator, permission, Timestamp.from(issued), Timestamp.from(expires));
    }

    HttpResponse<String> signIn(String subject) throws Exception {
        var challenge = call("POST", "auth/google/challenge", "{}", true);
        assertThat(challenge.statusCode()).isEqualTo(200);
        String id = JsonPath.read(challenge.body(), "$.id"), nonce = JsonPath.read(challenge.body(), "$.nonce");
        return call("POST", "auth/google/exchange",
                "{\"challengeId\":\"" + id + "\",\"idToken\":\"" + nonce + "|" + subject + "\"}", true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"spots_review", "spots_hide", "spots_restrict", "spots_alias_lookup", "spots_grant_admin"})
    void eachSpotsPermissionAloneCanSignIn(String permission) throws Exception {
        String subject = "subject-" + permission;
        UUID operator = account(subject);
        grant(operator, permission, Duration.ofHours(1));
        var exchange = signIn(subject);
        assertThat(exchange.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(exchange.body(), "$.accountId")).isEqualTo(operator.toString());
        Instant expires = Instant.parse(JsonPath.read(exchange.body(), "$.expiresAt"));
        Instant absolute = Instant.parse(JsonPath.read(exchange.body(), "$.absoluteExpiresAt"));
        assertThat(Duration.between(expires, absolute)).isBetween(Duration.ofHours(7).plusMinutes(44),
                Duration.ofHours(7).plusMinutes(46));
        assertThat(call("GET", "auth/session", "", false).statusCode()).isEqualTo(200);
    }

    @Test void noGrantExpiredGrantAndRevokedSessionAreDenied() throws Exception {
        account("no-grant");
        assertThat(signIn("no-grant").statusCode()).isEqualTo(401);
        UUID expired = account("expired-grant");
        grant(expired, "spots_review", Duration.ofSeconds(-1));
        assertThat(signIn("expired-grant").statusCode()).isEqualTo(401);
        UUID operator = account("revoked-session");
        grant(operator, "spots_review", Duration.ofHours(1));
        assertThat(signIn("revoked-session").statusCode()).isEqualTo(200);
        assertThat(call("POST", "auth/logout", "{}", true).statusCode()).isEqualTo(204);
        assertThat(call("GET", "auth/session", "", false).statusCode()).isEqualTo(401);
        assertThat(call("POST", "auth/session/renew", "{}", true).statusCode()).isEqualTo(401);
    }

    @Test void sessionsRenewWhileActiveButNeverPastEightHours() throws Exception {
        UUID operator = account("renewing");
        grant(operator, "spots_hide", Duration.ofHours(12));
        assertThat(signIn("renewing").statusCode()).isEqualTo(200);
        assertThat(call("POST", "auth/session/renew", "{}", false).statusCode()).isEqualTo(403); // CSRF and origin.

        jdbc.update("UPDATE admin_auth_session SET expires_at = clock_timestamp() + INTERVAL '1 minute' WHERE account_id = ?",
                operator);
        var renewed = call("POST", "auth/session/renew", "{}", true);
        assertThat(renewed.statusCode()).isEqualTo(200);
        assertThat(renewed.headers().allValues("Set-Cookie")).anySatisfy(cookie ->
                assertThat(cookie).contains("routiqo_admin_session=", "HttpOnly", "SameSite=Strict"));
        Instant expires = Instant.parse(JsonPath.read(renewed.body(), "$.expiresAt"));
        assertThat(Duration.between(Instant.now(), expires)).isBetween(Duration.ofMinutes(14), Duration.ofMinutes(16));

        // Near the absolute limit, renewal stops at it.
        jdbc.update("""
                UPDATE admin_auth_session SET created_at = clock_timestamp() - INTERVAL '7 hours 59 minutes',
                    absolute_expires_at = clock_timestamp() + INTERVAL '30 seconds',
                    expires_at = clock_timestamp() + INTERVAL '10 seconds' WHERE account_id = ?
                """, operator);
        var capped = call("POST", "auth/session/renew", "{}", true);
        assertThat(capped.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(capped.body(), "$.expiresAt"))
                .isEqualTo(JsonPath.<String>read(capped.body(), "$.absoluteExpiresAt"));

        // Past the absolute limit the session is over; the operator signs in again.
        jdbc.update("""
                UPDATE admin_auth_session SET absolute_expires_at = clock_timestamp() - INTERVAL '1 second',
                    expires_at = clock_timestamp() - INTERVAL '1 second' WHERE account_id = ?
                """, operator);
        assertThat(call("POST", "auth/session/renew", "{}", true).statusCode()).isEqualTo(401);
        assertThat(call("GET", "auth/session", "", false).statusCode()).isEqualTo(401);
    }

    @Test void renewalChecksTheGrantEveryTime() throws Exception {
        UUID operator = account("grant-revoked");
        grant(operator, "spots_review", Duration.ofHours(1));
        assertThat(signIn("grant-revoked").statusCode()).isEqualTo(200);
        jdbc.update("DELETE FROM moderation_operator_grant WHERE operator_id = ?", operator);
        assertThat(call("POST", "auth/session/renew", "{}", true).statusCode()).isEqualTo(401);
    }

    @Test void theDatabaseRefusesSessionsLongerThanEightHours() {
        UUID operator = account("db-limit");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO admin_auth_session(token_hash, account_id, created_at, expires_at, absolute_expires_at)
                VALUES (repeat('a', 64), ?, clock_timestamp(), clock_timestamp() + INTERVAL '10 minutes',
                    clock_timestamp() + INTERVAL '9 hours')
                """, operator)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void featurePathsStayClosedWithoutTheirOwnFlags() throws Exception {
        UUID operator = account("closed-paths");
        grant(operator, "spots_grant_admin", Duration.ofHours(1));
        assertThat(signIn("closed-paths").statusCode()).isEqualTo(200);
        for (String path : new String[] {"spots/reports", "spot-grants/me", "community-traffic/reports",
                "traffic-grants/me"})
            assertThat(call("GET", path, "", false).statusCode()).as(path).isIn(401, 403); // denyAll, no handler reached
    }

    @Test void maintenancePurgesExpiredSignInStateAndSpotsGrantsOnly() {
        UUID operator = account("maintenance");
        grant(operator, "spots_review", Duration.ofSeconds(-60));
        grant(operator, "spots_hide", Duration.ofHours(1));
        grant(operator, "traffic_review", Duration.ofSeconds(-60));
        jdbc.update("""
                INSERT INTO admin_auth_session(token_hash, account_id, created_at, expires_at, absolute_expires_at)
                VALUES (repeat('b', 64), ?, clock_timestamp() - INTERVAL '1 hour', clock_timestamp() - INTERVAL '1 minute',
                    clock_timestamp() - INTERVAL '1 minute'),
                       (repeat('c', 64), ?, clock_timestamp(), clock_timestamp() + INTERVAL '10 minutes',
                    clock_timestamp() + INTERVAL '10 minutes')
                """, operator, operator);
        cleanup.cleanup(100);
        assertThat(jdbc.queryForList("SELECT permission FROM moderation_operator_grant WHERE operator_id = ? ORDER BY 1",
                String.class, operator)).containsExactly("spots_hide", "traffic_review");
        assertThat(jdbc.queryForList("SELECT token_hash FROM admin_auth_session WHERE account_id = ?", String.class,
                operator)).containsExactly("c".repeat(64));
    }
}
