package com.routiqo.core.privacy.api;

import com.jayway.jsonpath.JsonPath;
import com.routiqo.core.identity.application.*;
import com.routiqo.core.identity.infrastructure.JdbcAccountWriteAuthority;
import com.routiqo.core.identity.infrastructure.JdbcAuthRateGate;
import com.routiqo.core.identity.infrastructure.JdbcSessionStore;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com",
    "ROUTIQO_WEB_ORIGIN=http://localhost:3000",
    "ROUTIQO_AUTH_SECURE_COOKIES=false",
    "ROUTIQO_LIVE_CONSENT_API_ENABLED=true"
})
@ActiveProfiles({"persistence", "google-auth", "web-auth"})
@Import(BrowserConsentHttpTest.TestIdentity.class)
class BrowserConsentHttpTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("ROUTIQO_AUTH_RATE_SECRET", () -> UUID.randomUUID().toString());
    }

    @TestConfiguration static class TestIdentity {
        @Bean @Primary GoogleIdentityVerifier syntheticIdentity() {
            return (token, nonce) -> {
                if (!token.startsWith(nonce + ":")) throw new SecurityException("Synthetic credential rejected");
                return new GoogleIdentityVerifier.Identity(
                        "google", UUID.fromString(token.substring(nonce.length() + 1)).toString());
            };
        }
        @Bean @Qualifier("peerRateUnavailable") AtomicBoolean peerRateUnavailable() {
            return new AtomicBoolean();
        }
        @Bean @Qualifier("accountRateUnavailable") AtomicBoolean accountRateUnavailable() {
            return new AtomicBoolean();
        }
        @Bean @Primary AuthRateGate controlledRates(JdbcTemplate jdbc,
                @Value("${ROUTIQO_AUTH_RATE_SECRET}") String secret,
                @Qualifier("peerRateUnavailable") AtomicBoolean peerUnavailable,
                @Qualifier("accountRateUnavailable") AtomicBoolean accountUnavailable) {
            AuthRateGate delegate = new JdbcAuthRateGate(jdbc, secret, Clock.fixed(
                    java.time.Instant.parse("2026-09-13T08:00:00Z"), java.time.ZoneOffset.UTC));
            return (identity, category, limit) -> {
                if ((category.equals("other") && peerUnavailable.get())
                        || (category.startsWith("consent-") && accountUnavailable.get())) {
                    throw new IllegalStateException("Synthetic rate failure");
                }
                return delegate.allow(identity, category, limit);
            };
        }
        @Bean @Qualifier("accountWriteUnavailable") AtomicBoolean accountWriteUnavailable() {
            return new AtomicBoolean();
        }
        @Bean @Qualifier("sessionReadUnavailable") AtomicBoolean sessionReadUnavailable() {
            return new AtomicBoolean();
        }
        @Bean @Primary GoogleSessionService controlledSessions(JdbcTemplate jdbc,
                DataSource dataSource, GoogleIdentityVerifier verifier,
                @Qualifier("sessionReadUnavailable") AtomicBoolean unavailable) {
            SessionStore delegate = new JdbcSessionStore(
                    jdbc, new DataSourceTransactionManager(dataSource));
            SessionStore controlled = new SessionStore() {
                @Override public void createChallenge(UUID id, String nonce, String bindingHash,
                        java.time.Instant now, java.time.Instant expiresAt) {
                    delegate.createChallenge(id, nonce, bindingHash, now, expiresAt);
                }
                @Override public String challengeNonce(UUID id, String bindingHash, java.time.Instant now) {
                    return delegate.challengeNonce(id, bindingHash, now);
                }
                @Override public UUID exchange(UUID id, String bindingHash,
                        GoogleIdentityVerifier.Identity identity, String tokenHash,
                        java.time.Instant now, java.time.Instant expiresAt) {
                    return delegate.exchange(id, bindingHash, identity, tokenHash, now, expiresAt);
                }
                @Override public UUID authenticate(String tokenHash, java.time.Instant now) {
                    if (unavailable.get()) {
                        throw new DataAccessResourceFailureException("synthetic private session failure");
                    }
                    return delegate.authenticate(tokenHash, now);
                }
                @Override public Renewal renew(String tokenHash, String replacementHash,
                        java.time.Instant now) {
                    return delegate.renew(tokenHash, replacementHash, now);
                }
                @Override public void deleteAccount(String tokenHash, java.time.Instant now) {
                    delegate.deleteAccount(tokenHash, now);
                }
                @Override public java.util.Optional<UUID> revocationAccount(String tokenHash) {
                    return delegate.revocationAccount(tokenHash);
                }
                @Override public void revoke(String tokenHash, java.time.Instant now) {
                    delegate.revoke(tokenHash, now);
                }
            };
            return new GoogleSessionService(controlled, verifier, Clock.systemUTC());
        }
        @Bean @Primary AccountWriteAuthority controlledAccountWrites(JdbcTemplate jdbc,
                DataSource dataSource,
                @Qualifier("accountWriteUnavailable") AtomicBoolean unavailable) {
            var delegate = new JdbcAccountWriteAuthority(
                    jdbc, new DataSourceTransactionManager(dataSource));
            return new AccountWriteAuthority() {
                @Override public <T> T withEnabledAccount(UUID actorId, Work<T> work) {
                    if (unavailable.get()) throw new AccountWriteUnavailable();
                    return delegate.withEnabledAccount(actorId, work);
                }
            };
        }
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("peerRateUnavailable") AtomicBoolean peerRateUnavailable;
    @Autowired @Qualifier("accountRateUnavailable") AtomicBoolean accountRateUnavailable;
    @Autowired @Qualifier("accountWriteUnavailable") AtomicBoolean accountWriteUnavailable;
    @Autowired @Qualifier("sessionReadUnavailable") AtomicBoolean sessionReadUnavailable;

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM auth_rate_bucket");
        peerRateUnavailable.set(false);
        accountRateUnavailable.set(false);
        accountWriteUnavailable.set(false);
        sessionReadUnavailable.set(false);
    }

    record Browser(HttpClient client, String csrf, String account, UUID subject) {}

    @Test void ownerRoundTripUsesStringGenerationsAndSubmitIntentOrdering() throws Exception {
        Browser owner = login(UUID.randomUUID());
        UUID journey = start(owner);
        assertConsent(send(owner, "GET", path(journey), null), journey, "0", false, true);
        assertConsent(post(owner, journey, "0", false), journey, "1", false, true);
        assertEmpty(post(owner, journey, "0", true), 409);
        assertConsent(post(owner, journey, "1", true), journey, "2", true, true);
        assertConsent(post(owner, journey, "2", true), journey, "3", true, true);
        assertConsent(post(owner, journey, "1", false), journey, "4", false, true);
        assertEmpty(post(owner, journey, "5", false), 409);
    }

    @Test void completedAndReplacedJourneysNeverMutateTheNewerBinding() throws Exception {
        Browser owner = login(UUID.randomUUID());
        UUID first = start(owner);
        assertConsent(post(owner, first, "0", true), first, "1", true, true);
        assertThat(send(owner, "POST", "journeys/" + first + "/complete", "{}").statusCode())
                .isEqualTo(200);
        assertConsent(send(owner, "GET", path(first), null), first, "2", false, false);
        assertEmpty(post(owner, first, "2", true), 409);
        UUID second = start(owner);
        assertConsent(post(owner, second, "0", false), second, "1", false, true);
        assertConsent(post(owner, first, "999", false), first, "0", false, false);
        assertConsent(send(owner, "GET", path(second), null), second, "1", false, true);
        Browser other = login(UUID.randomUUID());
        assertEmpty(send(other, "GET", path(second), null), 404);
        assertEmpty(post(other, second, "0", false), 404);
    }

    @Test void exactMaximumGenerationIsPreservedAndTerminalRevocationSaturates() throws Exception {
        Browser owner = login(UUID.randomUUID());
        UUID journey = start(owner);
        assertConsent(post(owner, journey, "0", true), journey, "1", true, true);
        jdbc.update("""
            UPDATE presence_consent SET generation = ?, sharing = TRUE
            WHERE actor_id = ? AND journey_id = ?
            """, Long.MAX_VALUE, UUID.fromString(owner.account()), journey);
        assertConsent(post(owner, journey, "9223372036854775807", false), journey,
                "9223372036854775807", false, true);
        assertEmpty(post(owner, journey, "9223372036854775807", true), 409);
    }

    @Test void strictJsonPathMediaAndBodyBoundsFailClosed() throws Exception {
        Browser owner = login(UUID.randomUUID());
        UUID journey = start(owner);
        String target = path(journey);
        for (String invalid : java.util.List.of(
                "{}", "null", "[]", "{", "{\"expectedGeneration\":null,\"sharing\":false}",
                "{\"expectedGeneration\":0,\"sharing\":false}",
                "{\"expectedGeneration\":\"0\",\"sharing\":\"false\"}",
                "{\"expectedGeneration\":\"00\",\"sharing\":false}",
                "{\"expectedGeneration\":\"+1\",\"sharing\":false}",
                "{\"expectedGeneration\":\" 1\",\"sharing\":false}",
                "{\"expectedGeneration\":\"1e0\",\"sharing\":false}",
                "{\"expectedGeneration\":\"9223372036854775808\",\"sharing\":false}",
                "{\"expectedGeneration\":\"0\",\"sharing\":false,\"extra\":1}",
                "{\"expectedGeneration\":\"0\",\"expectedGeneration\":\"0\",\"sharing\":false}",
                "{\"expectedGeneration\":\"0\",\"sharing\":false}{}")) {
            assertEmpty(send(owner, "POST", target, invalid), 400);
        }
        assertEmpty(send(owner, "POST", target, "x".repeat(20 * 1024 + 1)), 413);
        assertEmpty(send(owner.client(), "POST", target, body("0", false), "text/plain",
                owner.csrf(), owner.account(), null), 415);
        assertEmpty(send(owner, "GET", "journeys/00000000-0000-0000-0000-000000000000/consent", null), 400);
        assertEmpty(send(owner, "GET", "journeys/" + journey.toString().toUpperCase() + "/consent", null), 400);
        assertEmpty(send(owner, "GET", "journeys/not-a-uuid/consent", null), 400);
        assertEmpty(send(owner, "GET", target + "?actor=" + owner.account(), null), 400);
        assertDenied(send(owner, "PUT", target, "{}"));
        assertDenied(send(owner, "DELETE", target, null));
    }

    @Test void browserGuardsRunBeforeConsentDisclosure() throws Exception {
        Browser owner = login(UUID.randomUUID());
        UUID journey = start(owner);
        assertEmpty(send(client(), "GET", path(journey), null, null, null, null, null), 401);
        assertEmpty(sendWithBearerOnly(journey), 401);
        assertEmpty(send(owner.client(), "GET", path(journey), null, null, null,
                UUID.randomUUID().toString(), null), 401);
        assertEmpty(sendWithDuplicateAccount(owner, journey), 401);
        assertEmpty(send(owner.client(), "GET", path(journey), null, null, null,
                owner.account(), "cross-site"), 403);
        assertEmpty(send(owner.client(), "POST", path(journey), body("0", false),
                "application/json", null, owner.account(), null), 403);
        assertEmpty(send(owner.client(), "POST", path(journey), body("0", false),
                "application/json", owner.csrf(), owner.account(), null, "https://attacker.invalid"), 403);
        Browser missing = new Browser(client(), owner.csrf(), owner.account(), owner.subject());
        assertEmpty(send(missing, "GET", path(journey), null), 401);
        assertThat(send(owner, "POST", "auth/logout", "{}").statusCode()).isEqualTo(204);
        assertEmpty(send(owner, "GET", path(journey), null), 401);
        Browser expired = login(UUID.randomUUID());
        UUID expiredJourney = start(expired);
        jdbc.update("""
            UPDATE auth_session
            SET created_at = now() - interval '1 hour',
                authenticated_at = now() - interval '1 hour',
                expires_at = now() - interval '30 minutes'
            WHERE account_id = ?
            """,
                UUID.fromString(expired.account()));
        assertEmpty(send(expired, "GET", path(expiredJourney), null), 401);
    }

    @Test void browserOnlyProfileDoesNotExposeNativeConsent() throws Exception {
        String path = "http://localhost:" + port + "/api/v1/native/journeys/" + UUID.randomUUID() + "/consent";
        var request = HttpRequest.newBuilder(URI.create(path)).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isIn(401, 403);
        assertThat(response.body()).isEmpty();
    }

    @Test void accountBudgetsSpanSessionsAndJourneysAndStaySeparate() throws Exception {
        UUID subject = UUID.randomUUID();
        Browser first = login(subject);
        Browser second = login(subject);
        assertThat(second.account()).isEqualTo(first.account());
        UUID firstJourney = start(first);
        for (int attempt = 0; attempt < 30; attempt++)
            assertThat(send(attempt % 2 == 0 ? first : second, "GET", path(firstJourney), null).statusCode())
                    .isEqualTo(200);
        assertThat(send(first, "POST", "journeys/" + firstJourney + "/complete", "{}").statusCode())
                .isEqualTo(200);
        UUID secondJourney = start(second);
        for (int attempt = 0; attempt < 30; attempt++)
            assertThat(send(attempt % 2 == 0 ? first : second, "GET", path(secondJourney), null).statusCode())
                    .isEqualTo(200);
        assertLimited(send(first, "GET", path(secondJourney), null));

        String generation = "0";
        for (int attempt = 0; attempt < 10; attempt++) {
            var response = post(attempt % 2 == 0 ? first : second, secondJourney, generation, true);
            assertThat(response.statusCode()).isEqualTo(200);
            generation = JsonPath.read(response.body(), "$.generation");
        }
        assertLimited(post(first, secondJourney, generation, true));
        for (int attempt = 0; attempt < 20; attempt++)
            assertThat(post(attempt % 2 == 0 ? first : second, secondJourney, "0", false).statusCode())
                    .isEqualTo(200);
        assertLimited(post(first, secondJourney, "0", false));
    }

    @Test void persistenceAndRateFailuresAreSanitizedUnavailableResponses() throws Exception {
        Browser owner = login(UUID.randomUUID());
        UUID journey = start(owner);
        accountWriteUnavailable.set(true);
        assertEmpty(send(owner, "GET", path(journey), null), 503);
        assertEmpty(post(owner, journey, "0", false), 503);
        accountWriteUnavailable.set(false);
        accountRateUnavailable.set(true);
        assertEmpty(send(owner, "GET", path(journey), null), 503);
        accountRateUnavailable.set(false);
        peerRateUnavailable.set(true);
        assertEmpty(send(owner, "GET", path(journey), null), 503);
        peerRateUnavailable.set(false);
        sessionReadUnavailable.set(true);
        assertEmpty(send(owner, "GET", path(journey), null), 503);
    }

    @Test void privateRequestAndResponseDiagnosticsAreRedacted() {
        UUID journey = UUID.randomUUID();
        assertThat(new BrowserConsentJson.Intent(Long.MAX_VALUE, true).toString())
                .isEqualTo("BrowserConsentIntent[private]")
                .doesNotContain(Long.toString(Long.MAX_VALUE));
        assertThat(new BrowserConsentController.ConsentResponse(
                journey, Long.toString(Long.MAX_VALUE), true, true).toString())
                .isEqualTo("BrowserConsentResponse[private]")
                .doesNotContain(journey.toString(), Long.toString(Long.MAX_VALUE));
    }

    private Browser login(UUID subject) throws Exception {
        HttpClient client = client();
        String csrf = JsonPath.read(send(client, "GET", "auth/csrf", null,
                null, null, null, null).body(), "$.token");
        var challenge = send(client, "POST", "auth/google/challenge", "{}",
                "application/json", csrf, null, null);
        String id = JsonPath.read(challenge.body(), "$.id");
        String nonce = JsonPath.read(challenge.body(), "$.nonce");
        var exchange = send(client, "POST", "auth/google/exchange",
                "{\"challengeId\":\"" + id + "\",\"idToken\":\"" + nonce + ":" + subject + "\"}",
                "application/json", csrf, null, null);
        assertThat(exchange.statusCode()).isEqualTo(200);
        return new Browser(client, csrf, JsonPath.read(exchange.body(), "$.accountId"), subject);
    }

    private UUID start(Browser browser) throws Exception {
        UUID id = UUID.randomUUID();
        var response = send(browser, "POST", "journeys",
                "{\"id\":\"" + id + "\",\"kind\":\"trip\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        return id;
    }
    private HttpResponse<String> post(Browser browser, UUID journey, String generation,
            boolean sharing) throws Exception {
        return send(browser, "POST", path(journey), body(generation, sharing));
    }
    private static String body(String generation, boolean sharing) {
        return "{\"expectedGeneration\":\"" + generation + "\",\"sharing\":" + sharing + "}";
    }
    private static String path(UUID journey) { return "journeys/" + journey + "/consent"; }
    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(
                new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    private HttpResponse<String> send(Browser browser, String method, String path, String body)
            throws Exception {
        return send(browser.client(), method, path, body, body == null ? null : "application/json",
                browser.csrf(), browser.account(), null);
    }
    private HttpResponse<String> send(HttpClient client, String method, String path, String body,
            String contentType, String csrf, String account, String fetchSite) throws Exception {
        return send(client, method, path, body, contentType, csrf, account, fetchSite,
                method.equals("POST") ? "http://localhost:3000" : null);
    }
    private HttpResponse<String> send(HttpClient client, String method, String path, String body,
            String contentType, String csrf, String account, String fetchSite, String origin)
            throws Exception {
        var builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/" + path)).timeout(Duration.ofSeconds(10));
        if (contentType != null) builder.header("Content-Type", contentType);
        if (csrf != null) builder.header("X-XSRF-TOKEN", csrf);
        if (account != null) builder.header("X-Routiqo-Account", account);
        if (fetchSite != null) builder.header("Sec-Fetch-Site", fetchSite);
        if (origin != null) builder.header("Origin", origin);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> sendWithDuplicateAccount(Browser browser, UUID journey)
            throws Exception {
        var request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/" + path(journey)))
                .header("X-Routiqo-Account", browser.account())
                .header("X-Routiqo-Account", browser.account()).GET().build();
        return browser.client().send(request, HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> sendWithBearerOnly(UUID journey) throws Exception {
        var request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/" + path(journey)))
                .header("Authorization", "Bearer ignored")
                .header("X-Routiqo-Account", UUID.randomUUID().toString()).GET().build();
        return client().send(request, HttpResponse.BodyHandlers.ofString());
    }
    private static void assertConsent(HttpResponse<String> response, UUID journey,
            String generation, boolean sharing, boolean active) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(JsonPath.<String>read(response.body(), "$.journeyId")).isEqualTo(journey.toString());
        assertThat(JsonPath.<String>read(response.body(), "$.generation")).isEqualTo(generation);
        assertThat(JsonPath.<Boolean>read(response.body(), "$.sharing")).isEqualTo(sharing);
        assertThat(JsonPath.<Boolean>read(response.body(), "$.journeyActive")).isEqualTo(active);
        assertThat(response.body()).doesNotContain("actorId");
    }
    private static void assertLimited(HttpResponse<String> response) {
        assertEmpty(response, 429);
        assertThat(response.headers().firstValue("Retry-After")).contains("60");
    }
    private static void assertDenied(HttpResponse<String> response) {
        assertThat(response.statusCode()).isIn(401, 403);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }
    private static void assertEmpty(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }
}
