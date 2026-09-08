package com.routiqo.core.identity.api;

import com.routiqo.core.identity.application.GoogleIdentityVerifier;
import com.routiqo.core.identity.infrastructure.*;
import com.routiqo.core.security.BrowserAuthPolicy;
import com.jayway.jsonpath.JsonPath;
import java.net.*;
import java.net.http.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com",
    "ROUTIQO_WEB_ORIGIN=http://localhost:3000", "ROUTIQO_AUTH_SECURE_COOKIES=false"
})
@ActiveProfiles({"persistence", "google-auth", "web-auth"})
@Import(BrowserAuthHttpTest.TestIdentity.class)
class BrowserAuthHttpTest {
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("ROUTIQO_AUTH_RATE_SECRET", () -> UUID.randomUUID().toString());
    }
    @TestConfiguration static class TestIdentity {
        @Bean @Primary Clock fixedRateClock() { return Clock.fixed(java.time.Instant.parse("2026-09-08T12:00:00Z"), java.time.ZoneOffset.UTC); }
        @Bean @Primary GoogleIdentityVerifier localTestVerifier() {
            return (token, nonce) -> {
                if (!nonce.equals(token)) throw new SecurityException("Test verification rejected");
                return new GoogleIdentityVerifier.Identity("google", "http-test-subject");
            };
        }
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    HttpClient client;
    @BeforeEach void client() {
        jdbc.update("DELETE FROM auth_rate_bucket"); // Only this suite's disposable database.
        client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    HttpResponse<String> request(String method, String path, String body, String csrf, String origin) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/" + path));
        if (origin != null) builder.header("Origin", origin);
        if (csrf != null) builder.header("X-XSRF-TOKEN", csrf);
        if (method.equals("POST")) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        else builder.GET();
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    String csrf() throws Exception {
        var response = request("GET", "csrf", "", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.token");
    }
    @Test void loginSessionAndLogoutKeepSecretsInCookies() throws Exception {
        String csrf = csrf();
        var challenge = request("POST", "google/challenge", "{}", csrf, "http://localhost:3000");
        assertThat(challenge.statusCode()).isEqualTo(200);
        assertThat(challenge.body()).doesNotContain("binding", "credential");
        assertThat(challenge.headers().allValues("Set-Cookie")).anySatisfy(cookie ->
            assertThat(cookie).contains("routiqo_binding=", "HttpOnly", "SameSite=Strict", "Path=/").doesNotContain("Domain="));
        String id = JsonPath.read(challenge.body(), "$.id"), nonce = JsonPath.read(challenge.body(), "$.nonce");
        String body = "{\"challengeId\":\"" + id + "\",\"idToken\":\"" + nonce + "\"}";
        var exchange = request("POST", "google/exchange", body, csrf, "http://localhost:3000");
        assertThat(exchange.statusCode()).isEqualTo(200);
        assertThat(exchange.body()).doesNotContain("credential", "idToken", "binding");
        assertThat(exchange.headers().firstValue("Cache-Control")).contains("no-store");
        String account = JsonPath.read(exchange.body(), "$.accountId");
        assertThat(request("GET", "session", "", null, null).body()).contains(account);
        assertThat(request("POST", "session/renew", "{}", null, "http://localhost:3000").statusCode()).isEqualTo(403);
        var renewed = request("POST", "session/renew", "{}", csrf, "http://localhost:3000");
        assertThat(renewed.statusCode()).isEqualTo(200);
        assertThat(renewed.body()).contains(account).doesNotContain("credential", "token_hash");
        assertThat(renewed.headers().allValues("Set-Cookie")).anySatisfy(cookie ->
            assertThat(cookie).contains("routiqo_session=", "HttpOnly", "SameSite=Strict"));
        assertThat(request("POST", "google/exchange", body, csrf, "http://localhost:3000").statusCode()).isEqualTo(401);
        assertThat(request("POST", "logout", "{}", csrf, "http://localhost:3000").statusCode()).isEqualTo(204);
        assertThat(request("GET", "session", "", null, null).statusCode()).isEqualTo(401);
        assertThat(request("POST", "session/renew", "{}", csrf, "http://localhost:3000").statusCode()).isEqualTo(401);
    }
    @Test void originCsrfAndBodyLimitsRejectRequests() throws Exception {
        String csrf = csrf();
        assertThat(request("POST", "google/challenge", "{}", null, "http://localhost:3000").statusCode()).isEqualTo(403);
        assertThat(request("POST", "google/challenge", "{}", csrf, "https://attacker.invalid").statusCode()).isEqualTo(403);
        assertThat(request("POST", "google/challenge", "{}", csrf, null).statusCode()).isEqualTo(403);
        assertThat(request("POST", "google/exchange", "x".repeat(20 * 1024 + 1), csrf, "http://localhost:3000").statusCode()).isEqualTo(413);
        assertThat(request("POST", "google/exchange", "{", csrf, "http://localhost:3000").statusCode()).isEqualTo(400);
        assertThat(request("POST", "google/exchange", "{}", csrf, "http://localhost:3000").statusCode()).isEqualTo(400);
        var wrongType = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/google/challenge"))
                .header("Origin", "http://localhost:3000").header("X-XSRF-TOKEN", csrf)
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("{}"));
        assertThat(client.send(wrongType.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(415);
    }
    String login(String csrf) throws Exception {
        var challenge = request("POST", "google/challenge", "{}", csrf, "http://localhost:3000");
        String id = JsonPath.read(challenge.body(), "$.id"), nonce = JsonPath.read(challenge.body(), "$.nonce");
        var response = request("POST", "google/exchange", "{\"challengeId\":\"" + id + "\",\"idToken\":\"" + nonce + "\"}", csrf, "http://localhost:3000");
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.accountId");
    }
    @Test void deletionRequiresCsrfExplicitAccountConfirmationAndFreshAuthentication() throws Exception {
        String csrf = csrf(), account = login(csrf);
        String body = "{\"confirmation\":\"DELETE\",\"accountId\":\"" + account + "\"}";
        assertThat(request("POST", "account/delete", body, null, "http://localhost:3000").statusCode()).isEqualTo(403);
        assertThat(request("POST", "account/delete", "{}", csrf, "http://localhost:3000").statusCode()).isEqualTo(400);
        assertThat(request("POST", "account/delete", body.replace(account, UUID.randomUUID().toString()), csrf, "http://localhost:3000").statusCode()).isEqualTo(401);
        jdbc.update("UPDATE auth_session SET authenticated_at = created_at - INTERVAL '6 minutes' WHERE account_id = ?", UUID.fromString(account));
        assertThat(request("POST", "account/delete", body, csrf, "http://localhost:3000").statusCode()).isEqualTo(428);
        assertThat(request("GET", "session", "", null, null).statusCode()).isEqualTo(200);
        assertThat(login(csrf)).isEqualTo(account);
        var deleted = request("POST", "account/delete", body, csrf, "http://localhost:3000");
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(deleted.headers().allValues("Set-Cookie")).anySatisfy(cookie -> assertThat(cookie).contains("routiqo_session=", "Max-Age=0"));
        assertThat(request("GET", "session", "", null, null).statusCode()).isEqualTo(401);
    }
    @Test void limitsChallengeRequestsAcrossTheDatabase() throws Exception {
        String csrf = csrf();
        for (int i = 0; i < 10; i++) assertThat(request("POST", "google/challenge", "{}", csrf, "http://localhost:3000").statusCode()).isEqualTo(200);
        var rejected = request("POST", "google/challenge", "{}", csrf, "http://localhost:3000");
        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(rejected.headers().firstValue("Retry-After")).contains("60");
    }
    @Test void cleanupIsBoundedAndKeepsUnexpiredRows() {
        jdbc.update("""
            INSERT INTO login_challenge (id, nonce, binding_hash, created_at, expires_at)
            SELECT gen_random_uuid(), repeat('n',43), repeat('a',64), CURRENT_TIMESTAMP - INTERVAL '2 days',
            CURRENT_TIMESTAMP - INTERVAL '1 day' FROM generate_series(1, 105)
            """);
        long before = jdbc.queryForObject("SELECT count(*) FROM login_challenge WHERE expires_at < CURRENT_TIMESTAMP", Long.class);
        long active = jdbc.queryForObject("SELECT count(*) FROM login_challenge WHERE expires_at >= CURRENT_TIMESTAMP", Long.class);
        new AuthMaintenance(jdbc).cleanExpired();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_challenge WHERE expires_at < CURRENT_TIMESTAMP", Long.class)).isEqualTo(before - 100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_challenge WHERE expires_at >= CURRENT_TIMESTAMP", Long.class)).isEqualTo(active);
    }
    @Test void productionCookiesRequireHttpsAndHaveSecureFlag() {
        assertThat(new BrowserAuthPolicy("https://routiqo.example", true).cookie("routiqo_session", "test", 900))
                .contains("__Host-routiqo_session=", "Secure", "HttpOnly", "SameSite=Strict").doesNotContain("Domain=");
        assertThatThrownBy(() -> new BrowserAuthPolicy("https://routiqo.example", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BrowserAuthPolicy("http://routiqo.example", false)).isInstanceOf(IllegalArgumentException.class);
        var first = new JdbcAuthRateGate(jdbc, "temporary-test-key-not-used-in-production", Clock.systemUTC());
        var second = new JdbcAuthRateGate(jdbc, "temporary-test-key-not-used-in-production", Clock.systemUTC());
        assertThat(first.allow("peer", "shared", 1)).isTrue();
        assertThat(second.allow("peer", "shared", 1)).isFalse();
    }
    @Test void concurrentRateChecksCannotOvershootTheLimit() throws Exception {
        Clock fixed = Clock.fixed(java.time.Instant.parse("2026-09-08T12:00:00Z"), java.time.ZoneOffset.UTC);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(10)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 10; i++) futures.add(executor.submit(() -> {
                if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Rate race did not start");
                return new JdbcAuthRateGate(jdbc, "temporary-test-key-not-used-in-production", fixed).allow("peer", "race", 5);
            }));
            start.countDown();
            int accepted = 0;
            for (var future : futures) if (future.get(20, java.util.concurrent.TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(5);
        }
    }
}
