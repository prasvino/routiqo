package com.routiqo.core.routeupdate.api;

import com.jayway.jsonpath.JsonPath;
import com.routiqo.core.identity.application.GoogleIdentityVerifier;
import com.routiqo.core.identity.application.*;
import com.routiqo.core.identity.infrastructure.*;
import com.routiqo.core.privacy.application.PresenceConsentService;
import com.sun.net.httpserver.HttpServer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com",
    "ROUTIQO_WEB_ORIGIN=http://localhost:3000",
    "ROUTIQO_AUTH_SECURE_COOKIES=false",
    "ROUTIQO_ROUTING_REGION_WEST=-1", "ROUTIQO_ROUTING_REGION_SOUTH=-1",
    "ROUTIQO_ROUTING_REGION_EAST=1", "ROUTIQO_ROUTING_REGION_NORTH=1",
    "ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED=true",
    "ROUTIQO_LIVE_ROUTE_BINDING_API_ENABLED=true"
})
@ActiveProfiles({"persistence", "google-auth", "web-auth", "routing"})
@Import(BrowserRouteContextHttpTest.TestIdentity.class)
class BrowserRouteContextHttpTest {
    private enum ProviderMode { ROUTE, FAR_ROUTE, NO_ROUTE, FAILURE, BLOCKED_ROUTE }
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    private static final AtomicInteger PROVIDER_CALLS = new AtomicInteger();
    private static final AtomicReference<ProviderMode> PROVIDER_MODE =
            new AtomicReference<>(ProviderMode.ROUTE);
    private static final AtomicReference<CountDownLatch> PROVIDER_ENTERED =
            new AtomicReference<>(new CountDownLatch(0));
    private static final AtomicReference<CountDownLatch> PROVIDER_RELEASE =
            new AtomicReference<>(new CountDownLatch(0));
    private static final HttpServer SERVER;
    private static final Path CATALOG;

    static {
        try {
            DATABASE.start();
            CATALOG = Files.createTempFile("routiqo-browser-binding-", ".json");
            Files.writeString(CATALOG, """
                {"version":"00000000-0000-4000-8000-000000000101","anchors":[
                  {"id":"00000000-0000-4000-8000-000000000103","longitude":0,"latitude":0,"categories":["QUEUE"]},
                  {"id":"00000000-0000-4000-8000-000000000102","longitude":0.0002,"latitude":0,"categories":["TRAFFIC"]}
                ]}
                """, StandardCharsets.UTF_8);
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            SERVER.createContext("/route", exchange -> {
                PROVIDER_CALLS.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                ProviderMode mode = PROVIDER_MODE.get();
                if (mode == ProviderMode.BLOCKED_ROUTE) {
                    PROVIDER_ENTERED.get().countDown();
                    await(PROVIDER_RELEASE.get());
                    mode = ProviderMode.ROUTE;
                }
                int status = mode == ProviderMode.NO_ROUTE ? 400
                        : mode == ProviderMode.FAILURE ? 500 : 200;
                String response = switch (mode) {
                    case NO_ROUTE -> "{\"error_code\":442}";
                    case FAILURE -> "private provider failure should not escape";
                    case FAR_ROUTE -> valhalla(0.48, 0.50, 0.52);
                    default -> valhalla(-0.02, 0, 0.02);
                };
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            SERVER.start();
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("ROUTIQO_AUTH_RATE_SECRET", () -> "browser-route-context-test-secret");
        properties.add("ROUTIQO_VALHALLA_ORIGIN",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        properties.add("ROUTIQO_PHOTON_ORIGIN",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        properties.add("ROUTIQO_LIVE_ANCHOR_CATALOG_PATH", CATALOG::toString);
    }

    @TestConfiguration static class TestIdentity {
        @Bean @Primary GoogleIdentityVerifier syntheticIdentity() {
            return (token, nonce) -> {
                if (!token.startsWith(nonce + ":")) throw new SecurityException("Rejected");
                return new GoogleIdentityVerifier.Identity("google",
                        UUID.fromString(token.substring(nonce.length() + 1)).toString());
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
            AuthRateGate delegate = new JdbcAuthRateGate(jdbc, secret, Clock.systemUTC());
            return (identity, category, limit) -> {
                if (category.equals("other") && peerUnavailable.get()
                        || category.startsWith("route-context-") && accountUnavailable.get()) {
                    throw new IllegalStateException("Synthetic rate failure");
                }
                return delegate.allow(identity, category, limit);
            };
        }
        @Bean @Qualifier("accountWriteUnavailable") AtomicBoolean accountWriteUnavailable() {
            return new AtomicBoolean();
        }
        @Bean @Primary AccountWriteAuthority controlledAccountWrites(JdbcTemplate jdbc,
                DataSource dataSource,
                @Qualifier("accountWriteUnavailable") AtomicBoolean unavailable) {
            var delegate = new JdbcAccountWriteAuthority(jdbc,
                    new DataSourceTransactionManager(dataSource));
            return new AccountWriteAuthority() {
                @Override public <T> T withEnabledAccount(UUID actorId, Work<T> work) {
                    if (unavailable.get()) throw new AccountWriteUnavailable();
                    return delegate.withEnabledAccount(actorId, work);
                }
            };
        }
        @Bean @Qualifier("sessionReadUnavailable") AtomicBoolean sessionReadUnavailable() {
            return new AtomicBoolean();
        }
        @Bean @Primary GoogleSessionService controlledSessions(JdbcTemplate jdbc,
                DataSource dataSource, GoogleIdentityVerifier verifier,
                @Qualifier("sessionReadUnavailable") AtomicBoolean unavailable) {
            SessionStore delegate = new JdbcSessionStore(jdbc,
                    new DataSourceTransactionManager(dataSource));
            SessionStore controlled = new SessionStore() {
                @Override public void createChallenge(UUID id, String nonce, String bindingHash,
                        java.time.Instant now, java.time.Instant expiresAt) {
                    delegate.createChallenge(id, nonce, bindingHash, now, expiresAt);
                }
                @Override public String challengeNonce(UUID id, String bindingHash,
                        java.time.Instant now) {
                    return delegate.challengeNonce(id, bindingHash, now);
                }
                @Override public UUID exchange(UUID id, String bindingHash,
                        GoogleIdentityVerifier.Identity identity, String tokenHash,
                        java.time.Instant now, java.time.Instant expiresAt) {
                    return delegate.exchange(id, bindingHash, identity, tokenHash, now, expiresAt);
                }
                @Override public UUID authenticate(String tokenHash, java.time.Instant now) {
                    if (unavailable.get()) throw new DataAccessResourceFailureException(
                            "synthetic private session failure");
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
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PresenceConsentService consents;
    @Autowired ApplicationContext context;
    @Autowired @Qualifier("peerRateUnavailable") AtomicBoolean peerRateUnavailable;
    @Autowired @Qualifier("accountRateUnavailable") AtomicBoolean accountRateUnavailable;
    @Autowired @Qualifier("accountWriteUnavailable") AtomicBoolean accountWriteUnavailable;
    @Autowired @Qualifier("sessionReadUnavailable") AtomicBoolean sessionReadUnavailable;

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM auth_rate_bucket");
        PROVIDER_CALLS.set(0);
        PROVIDER_MODE.set(ProviderMode.ROUTE);
        PROVIDER_ENTERED.set(new CountDownLatch(0));
        PROVIDER_RELEASE.set(new CountDownLatch(0));
        peerRateUnavailable.set(false);
        accountRateUnavailable.set(false);
        accountWriteUnavailable.set(false);
        sessionReadUnavailable.set(false);
    }

    @AfterAll static void stop() throws Exception {
        SERVER.stop(0);
        Files.deleteIfExists(CATALOG);
    }

    private record Browser(HttpClient client, String csrf, UUID account) {}

    @Test void configuredOwnerCanBindReadAndRecoverTheMinimalPrivateContext() throws Exception {
        assertThat(context.getBeansOfType(BrowserRouteContextController.class)).hasSize(1);
        Browser owner = login(UUID.randomUUID());
        UUID journey = start(owner);
        enable(owner, journey);
        assertNullContext(get(owner, journey));

        HttpResponse<String> bound = post(owner, journey, routeBody(-0.02, 0.02, null));
        assertThat(bound.statusCode()).isEqualTo(200);
        assertThat(bound.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(JsonPath.<String>read(bound.body(), "$.status")).isEqualTo("bound");
        String contextId = JsonPath.read(bound.body(), "$.context.contextId");
        assertThat(JsonPath.<String>read(bound.body(), "$.context.revision")).isEqualTo("0");
        assertThat(JsonPath.<List<String>>read(bound.body(), "$.context.anchorIds")).containsExactly(
                "00000000-0000-4000-8000-000000000102",
                "00000000-0000-4000-8000-000000000103");
        Map<String, Object> contextFields = JsonPath.read(bound.body(), "$.context");
        assertThat(contextFields.keySet())
                .containsExactlyInAnyOrder("contextId", "revision", "anchorIds", "issuedAt", "expiresAt");
        assertThat(bound.body()).doesNotContain("actorId", "journeyId", "catalogVersion",
                "geometry", "origin", "destination");
        HttpResponse<String> recovered = get(owner, journey);
        assertThat(JsonPath.<String>read(recovered.body(), "$.context.contextId"))
                .isEqualTo(contextId);
        jdbc.update("UPDATE live_route_context SET revision = ? WHERE actor_id = ?",
                Long.MAX_VALUE, owner.account());
        assertThat(JsonPath.<String>read(get(owner, journey).body(), "$.context.revision"))
                .isEqualTo("9223372036854775807");
        assertThat(PROVIDER_CALLS).hasValue(1);
    }

    @Test void emptyProviderOutcomesPreserveThePreviouslyBoundContext() throws Exception {
        Browser owner = login(UUID.randomUUID());
        UUID journey = start(owner);
        enable(owner, journey);
        String contextId = JsonPath.read(post(owner, journey, routeBody(-0.02, 0.02, null)).body(),
                "$.context.contextId");

        PROVIDER_MODE.set(ProviderMode.NO_ROUTE);
        HttpResponse<String> noRoute = post(owner, journey, routeBody(-0.02, 0.02, contextId));
        assertOutcomeWithoutContext(noRoute, "no_route");
        assertThat(JsonPath.<String>read(get(owner, journey).body(), "$.context.contextId"))
                .isEqualTo(contextId);

        PROVIDER_MODE.set(ProviderMode.ROUTE);
        HttpResponse<String> noAnchors = post(owner, journey, routeBody(-0.005, 0.005, contextId));
        assertOutcomeWithoutContext(noAnchors, "no_eligible_anchors");
        assertThat(JsonPath.<String>read(get(owner, journey).body(), "$.context.contextId"))
                .isEqualTo(contextId);
    }

    @Test void strictInputPathAndBrowserGuardsRejectBeforeProviderWork() throws Exception {
        Browser owner = login(UUID.randomUUID());
        UUID journey = start(owner);
        enable(owner, journey);
        for (String invalid : List.of(
                "{}", "null", "[]", "{", routeBody(-0.02, 0.02, null) + "{}",
                "{\"mode\":\"driving\",\"origin\":[0,0],\"destination\":[1,1],\"alternativeIndex\":0,\"expectedContextId\":null,\"extra\":1}",
                "{\"mode\":\"flying\",\"origin\":[0,0],\"destination\":[1,1],\"alternativeIndex\":0,\"expectedContextId\":null}",
                "{\"mode\":\"driving\",\"origin\":[0],\"destination\":[1,1],\"alternativeIndex\":0,\"expectedContextId\":null}",
                "{\"mode\":\"driving\",\"origin\":[181,0],\"destination\":[1,1],\"alternativeIndex\":0,\"expectedContextId\":null}",
                "{\"mode\":\"driving\",\"origin\":[0,91],\"destination\":[1,1],\"alternativeIndex\":0,\"expectedContextId\":null}",
                "{\"mode\":\"driving\",\"origin\":[1e9999,0],\"destination\":[1,1],\"alternativeIndex\":0,\"expectedContextId\":null}",
                "{\"mode\":\"driving\",\"origin\":[0,0],\"destination\":[0,0],\"alternativeIndex\":0,\"expectedContextId\":null}",
                "{\"mode\":\"driving\",\"origin\":[0,0],\"destination\":[1,1],\"alternativeIndex\":0.0,\"expectedContextId\":null}",
                "{\"mode\":\"driving\",\"origin\":[0,0],\"destination\":[1,1],\"alternativeIndex\":3,\"expectedContextId\":null}",
                "{\"mode\":\"driving\",\"origin\":[0,0],\"destination\":[1,1],\"alternativeIndex\":0,\"expectedContextId\":\"00000000-0000-0000-0000-000000000000\"}",
                "{\"mode\":\"driving\",\"origin\":[0,0],\"destination\":[1,1],\"alternativeIndex\":0,\"expectedContextId\":\"AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA\"}",
                "{\"mode\":\"driving\",\"mode\":\"driving\",\"origin\":[0,0],\"destination\":[1,1],\"alternativeIndex\":0,\"expectedContextId\":null}")) {
            assertEmpty(send(owner, "POST", path(journey), invalid, "application/json",
                    owner.csrf(), owner.account().toString(), null, "http://localhost:3000"), 400);
        }
        assertEmpty(send(owner, "POST", path(journey), "x".repeat(20 * 1024 + 1),
                "application/json", owner.csrf(), owner.account().toString(), null,
                "http://localhost:3000"), 413);
        assertEmpty(send(owner, "POST", path(journey), routeBody(-0.02, 0.02, null),
                "text/plain", owner.csrf(), owner.account().toString(), null,
                "http://localhost:3000"), 415);
        assertEmpty(send(owner, "GET", path(journey) + "?retry=true", null, null,
                owner.csrf(), owner.account().toString(), null, null), 400);
        assertEmpty(send(owner, "GET", path(journey), null, null, null,
                UUID.randomUUID().toString(), null, null), 401);
        assertEmpty(send(owner, "GET", path(journey), null, null, null,
                null, null, null), 401);
        assertEmpty(send(owner, "GET", path(journey), null, null, null,
                owner.account().toString(), "cross-site", null), 403);
        assertEmpty(send(owner, "POST", path(journey), routeBody(-0.02, 0.02, null),
                "application/json", null, owner.account().toString(), null,
                "http://localhost:3000"), 403);
        assertEmpty(send(owner, "POST", path(journey), routeBody(-0.02, 0.02, null),
                "application/json", owner.csrf(), owner.account().toString(), null,
                "https://attacker.invalid"), 403);
        assertEmpty(sendRawCookie("routiqo_session=bad", journey), 401);
        assertEmpty(sendRawCookie("routiqo_session=bad; routiqo_session=also-bad", journey), 401);
        assertEmpty(sendDuplicateAccount(owner, journey), 401);
        assertEmpty(sendMalformedUtf8(owner, journey), 400);
        assertThat(send(owner, "PUT", path(journey), "{}", "application/json", owner.csrf(),
                owner.account().toString(), null, "http://localhost:3000").statusCode())
                .isIn(401, 403);
        assertThat(send(owner, "GET", path(journey) + "/replace", null, null, null,
                owner.account().toString(), null, null).statusCode()).isIn(401, 403);
        assertThat(PROVIDER_CALLS).hasValue(0);
    }

    @Test void ownershipConsentExpectationCompletionAndExpiryFailClosed() throws Exception {
        Browser owner = login(UUID.randomUUID());
        Browser other = login(UUID.randomUUID());
        UUID journey = start(owner);
        assertEmpty(get(other, journey), 404);
        assertEmpty(post(other, journey, routeBody(-0.02, 0.02, null)), 404);
        assertEmpty(post(owner, journey, routeBody(-0.02, 0.02, null)), 409);
        enable(owner, journey);
        String contextId = JsonPath.read(post(owner, journey, routeBody(-0.02, 0.02, null)).body(),
                "$.context.contextId");
        assertEmpty(post(owner, journey, routeBody(-0.02, 0.02, null)), 409);
        disable(owner, journey, 1);
        assertEmpty(post(owner, journey, routeBody(-0.02, 0.02, contextId)), 409);
        assertThat(PROVIDER_CALLS).hasValue(1);
        assertThat(JsonPath.<String>read(get(owner, journey).body(), "$.context.contextId"))
                .isEqualTo(contextId);

        jdbc.update("""
            UPDATE live_route_context
            SET issued_at = now() - interval '2 seconds',
                expires_at = now() - interval '1 second'
            WHERE actor_id = ?
            """,
                owner.account());
        assertNullContext(get(owner, journey));
        assertThat(postJourney(owner, journey, "complete").statusCode()).isEqualTo(200);
        assertNullContext(get(owner, journey));
    }

    @Test void consentRevokedWhileProviderIsBlockedPreventsStaleBinding() throws Exception {
        Browser owner = login(UUID.randomUUID());
        UUID journey = start(owner);
        enable(owner, journey);
        PROVIDER_MODE.set(ProviderMode.BLOCKED_ROUTE);
        PROVIDER_ENTERED.set(new CountDownLatch(1));
        PROVIDER_RELEASE.set(new CountDownLatch(1));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var request = executor.submit(() -> post(owner, journey,
                    routeBody(-0.02, 0.02, null)));
            assertThat(PROVIDER_ENTERED.get().await(5, TimeUnit.SECONDS)).isTrue();
            disable(owner, journey, 1);
            PROVIDER_RELEASE.get().countDown();
            assertEmpty(request.get(10, TimeUnit.SECONDS), 409);
        } finally {
            PROVIDER_RELEASE.get().countDown();
        }
        assertNullContext(get(owner, journey));
    }

    @Test void readAndBindingBudgetsAreSeparateAndSharedAcrossOwnerSessions() throws Exception {
        UUID subject = UUID.randomUUID();
        Browser first = login(subject);
        Browser second = login(subject);
        assertThat(second.account()).isEqualTo(first.account());
        UUID journey = start(first);
        enable(first, journey);
        for (int i = 0; i < 60; i++) assertThat(get(i % 2 == 0 ? first : second, journey).statusCode())
                .isEqualTo(200);
        assertLimited(get(first, journey));

        PROVIDER_MODE.set(ProviderMode.NO_ROUTE);
        for (int i = 0; i < 10; i++) assertThat(post(i % 2 == 0 ? first : second, journey,
                routeBody(-0.02, 0.02, null)).statusCode()).isEqualTo(200);
        assertLimited(post(first, journey, routeBody(-0.02, 0.02, null)));
        assertThat(PROVIDER_CALLS).hasValue(10);
    }

    @Test void providerFailureIsBodylessUnavailableAndDiagnosticsAreRedacted() throws Exception {
        Browser owner = login(UUID.randomUUID());
        UUID journey = start(owner);
        enable(owner, journey);
        PROVIDER_MODE.set(ProviderMode.FAILURE);
        HttpResponse<String> unavailable = post(owner, journey, routeBody(-0.02, 0.02, null));
        assertEmpty(unavailable, 503);
        assertThat(unavailable.body()).doesNotContain("provider", "private");
        UUID id = UUID.randomUUID();
        assertThat(new BrowserRouteContextJson.BindingRequest(
                new com.routiqo.core.routing.domain.RouteRequest(
                        com.routiqo.core.routing.domain.RouteRequest.Mode.DRIVING,
                        new com.routiqo.core.routing.domain.RouteRequest.Coordinate(0, 0),
                        new com.routiqo.core.routing.domain.RouteRequest.Coordinate(1, 1)),
                0, java.util.Optional.of(id)).toString())
                .isEqualTo("BrowserRouteContextRequest[private]").doesNotContain(id.toString());
    }

    @Test void sessionRateAndPersistenceFailuresAreSanitizedUnavailable() throws Exception {
        Browser owner = login(UUID.randomUUID());
        UUID journey = start(owner);
        enable(owner, journey);
        sessionReadUnavailable.set(true);
        assertEmpty(get(owner, journey), 503);
        sessionReadUnavailable.set(false);
        accountRateUnavailable.set(true);
        assertEmpty(get(owner, journey), 503);
        accountRateUnavailable.set(false);
        accountWriteUnavailable.set(true);
        assertEmpty(get(owner, journey), 503);
        assertEmpty(post(owner, journey, routeBody(-0.02, 0.02, null)), 503);
        accountWriteUnavailable.set(false);
        peerRateUnavailable.set(true);
        assertEmpty(get(owner, journey), 503);
        assertThat(PROVIDER_CALLS).hasValue(0);
    }

    private Browser login(UUID subject) throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(
                new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = JsonPath.read(send(client, "GET", "auth/csrf", null, null,
                null, null, null, null).body(), "$.token");
        HttpResponse<String> challenge = send(client, "POST", "auth/google/challenge", "{}",
                "application/json", csrf, null, null, "http://localhost:3000");
        String id = JsonPath.read(challenge.body(), "$.id");
        String nonce = JsonPath.read(challenge.body(), "$.nonce");
        HttpResponse<String> exchange = send(client, "POST", "auth/google/exchange",
                "{\"challengeId\":\"" + id + "\",\"idToken\":\"" + nonce + ":" + subject + "\"}",
                "application/json", csrf, null, null, "http://localhost:3000");
        assertThat(exchange.statusCode()).isEqualTo(200);
        return new Browser(client, csrf, UUID.fromString(JsonPath.read(exchange.body(), "$.accountId")));
    }

    private UUID start(Browser browser) throws Exception {
        UUID journey = UUID.randomUUID();
        HttpResponse<String> response = send(browser, "POST", "journeys",
                "{\"id\":\"" + journey + "\",\"kind\":\"trip\"}", "application/json",
                browser.csrf(), browser.account().toString(), null, "http://localhost:3000");
        assertThat(response.statusCode()).isEqualTo(200);
        return journey;
    }

    private void enable(Browser owner, UUID journey) {
        consents.submitIntent(owner.account(), journey, 0, true);
    }

    private void disable(Browser owner, UUID journey, long generation) {
        consents.submitIntent(owner.account(), journey, generation, false);
    }

    private HttpResponse<String> get(Browser browser, UUID journey) throws Exception {
        return send(browser, "GET", path(journey), null, null, null,
                browser.account().toString(), null, null);
    }

    private HttpResponse<String> post(Browser browser, UUID journey, String body) throws Exception {
        return send(browser, "POST", path(journey), body, "application/json", browser.csrf(),
                browser.account().toString(), null, "http://localhost:3000");
    }

    private HttpResponse<String> postJourney(Browser browser, UUID journey, String suffix)
            throws Exception {
        return send(browser, "POST", "journeys/" + journey + "/" + suffix, "{}",
                "application/json", browser.csrf(), browser.account().toString(), null,
                "http://localhost:3000");
    }

    private HttpResponse<String> send(Browser browser, String method, String path, String body,
            String contentType, String csrf, String account, String fetchSite, String origin)
            throws Exception {
        return send(browser.client(), method, path, body, contentType, csrf, account, fetchSite,
                origin);
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, String body,
            String contentType, String csrf, String account, String fetchSite, String origin)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/api/v1/" + path)).timeout(Duration.ofSeconds(30));
        if (contentType != null) builder.header("Content-Type", contentType);
        if (csrf != null) builder.header("X-XSRF-TOKEN", csrf);
        if (account != null) builder.header("X-Routiqo-Account", account);
        if (fetchSite != null) builder.header("Sec-Fetch-Site", fetchSite);
        if (origin != null) builder.header("Origin", origin);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendRawCookie(String cookie, UUID journey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/api/v1/" + path(journey)))
                .header("Cookie", cookie).header("X-Routiqo-Account", UUID.randomUUID().toString())
                .GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendDuplicateAccount(Browser owner, UUID journey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/api/v1/" + path(journey)))
                .header("X-Routiqo-Account", owner.account().toString())
                .header("X-Routiqo-Account", owner.account().toString()).GET().build();
        return owner.client().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendMalformedUtf8(Browser owner, UUID journey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/api/v1/" + path(journey)))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:3000")
                .header("X-XSRF-TOKEN", owner.csrf())
                .header("X-Routiqo-Account", owner.account().toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {(byte) 0xc3, 0x28}))
                .build();
        return owner.client().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String routeBody(double origin, double destination, String expected) {
        return "{\"mode\":\"driving\",\"origin\":[" + origin + ",0],\"destination\":["
                + destination + ",0],\"alternativeIndex\":0,\"expectedContextId\":"
                + (expected == null ? "null" : "\"" + expected + "\"") + "}";
    }

    private static String path(UUID journey) { return "journeys/" + journey + "/route-context"; }

    private static void assertNullContext(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(JsonPath.<Object>read(response.body(), "$.context")).isNull();
    }

    private static void assertOutcomeWithoutContext(HttpResponse<String> response, String status) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.status")).isEqualTo(status);
        assertThat(JsonPath.<Object>read(response.body(), "$.context")).isNull();
    }

    private static void assertLimited(HttpResponse<String> response) {
        assertEmpty(response, 429);
        assertThat(response.headers().firstValue("Retry-After")).contains("60");
    }

    private static void assertEmpty(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }

    private static String valhalla(double start, double middle, double end) {
        String shape = encode(List.of(new double[] {start, 0}, new double[] {middle, 0},
                new double[] {end, 0}));
        return "{\"trip\":{\"locations\":[{\"type\":\"break\"},{\"type\":\"break\"}],"
                + "\"legs\":[{\"maneuvers\":[{\"instruction\":\"Continue\",\"length\":4.4,"
                + "\"time\":60,\"begin_shape_index\":0,\"end_shape_index\":2}],"
                + "\"summary\":{\"length\":4.4,\"time\":60},\"shape\":\"" + shape + "\"}],"
                + "\"summary\":{\"length\":4.4,\"time\":60},\"status\":0,"
                + "\"units\":\"kilometers\",\"language\":\"en-US\"}}";
    }

    private static String encode(List<double[]> coordinates) {
        StringBuilder encoded = new StringBuilder();
        long previousLatitude = 0;
        long previousLongitude = 0;
        for (double[] coordinate : coordinates) {
            long longitude = Math.round(coordinate[0] * 1_000_000);
            long latitude = Math.round(coordinate[1] * 1_000_000);
            encodeValue(encoded, latitude - previousLatitude);
            encodeValue(encoded, longitude - previousLongitude);
            previousLatitude = latitude;
            previousLongitude = longitude;
        }
        return encoded.toString();
    }

    private static void encodeValue(StringBuilder encoded, long signed) {
        long value = signed < 0 ? ~(signed << 1) : signed << 1;
        while (value >= 0x20) {
            encoded.append((char) ((0x20 | (value & 0x1f)) + 63));
            value >>= 5;
        }
        encoded.append((char) (value + 63));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted");
        }
    }
}
