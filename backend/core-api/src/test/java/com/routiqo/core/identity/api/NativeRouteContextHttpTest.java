package com.routiqo.core.identity.api;

import com.jayway.jsonpath.JsonPath;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.infrastructure.JdbcAuthRateGate;
import com.routiqo.core.privacy.application.PresenceConsentService;
import com.routiqo.core.routeupdate.api.NativeRouteContextController;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com",
    "ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED=true",
    "ROUTIQO_NATIVE_LIVE_ROUTE_BINDING_API_ENABLED=true",
    "ROUTIQO_ROUTING_REGION_WEST=-1", "ROUTIQO_ROUTING_REGION_SOUTH=-1",
    "ROUTIQO_ROUTING_REGION_EAST=1", "ROUTIQO_ROUTING_REGION_NORTH=1"
})
@ActiveProfiles({"persistence", "google-auth", "native-auth", "routing"})
@Import({NativeAuthHttpTest.TestIdentity.class, NativeRouteContextHttpTest.Rates.class})
class NativeRouteContextHttpTest {
    private enum Mode { ROUTE, FAR, NO_ROUTE, FAILURE, BLOCKED }
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static volatile Mode MODE = Mode.ROUTE;
    private static volatile CountDownLatch ENTERED = new CountDownLatch(0);
    private static volatile CountDownLatch RELEASE = new CountDownLatch(0);
    private static final HttpServer PROVIDER = provider();
    private static final Path CATALOG = catalog();

    private static HttpServer provider() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/route", exchange -> {
                CALLS.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                Mode mode = MODE;
                if (mode == Mode.BLOCKED) {
                    ENTERED.countDown();
                    try {
                        if (!RELEASE.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("Fixture timeout");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Fixture interrupted");
                    }
                    mode = Mode.ROUTE;
                }
                int status = mode == Mode.NO_ROUTE ? 400 : mode == Mode.FAILURE ? 500 : 200;
                String body = mode == Mode.NO_ROUTE ? "{\"error_code\":442}"
                        : mode == Mode.FAILURE ? "fixture provider failure"
                        : valhalla(mode == Mode.FAR ? 0.5 : 0);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
            return server;
        } catch (Exception failure) { throw new IllegalStateException("Fixture unavailable", failure); }
    }

    private static Path catalog() {
        try {
            Path path = Files.createTempFile("routiqo-native-route-context-", ".json");
            Files.writeString(path, """
                    {"version":"00000000-0000-4000-8000-000000000101","anchors":[
                    {"id":"00000000-0000-4000-8000-000000000102","longitude":0,"latitude":0,
                     "categories":["TRAFFIC"],"displayLabel":"Central Junction"}]}
                    """, StandardCharsets.UTF_8);
            return path;
        } catch (Exception failure) { throw new IllegalStateException("Fixture unavailable", failure); }
    }

    private static String valhalla(double center) {
        String shape = encode(new double[][] {{center - 0.02, 0}, {center, 0}, {center + 0.02, 0}})
                .replace("\\", "\\\\");
        return "{\"trip\":{\"locations\":[{\"type\":\"break\"},{\"type\":\"break\"}],"
                + "\"legs\":[{\"maneuvers\":[{\"instruction\":\"Continue\",\"length\":4.4,"
                + "\"time\":60,\"begin_shape_index\":0,\"end_shape_index\":2}],"
                + "\"summary\":{\"length\":4.4,\"time\":60},\"shape\":\"" + shape + "\"}],"
                + "\"summary\":{\"length\":4.4,\"time\":60},\"status\":0,"
                + "\"units\":\"kilometers\",\"language\":\"en-US\"}}";
    }

    private static String encode(double[][] coordinates) {
        StringBuilder text = new StringBuilder();
        long priorLat = 0, priorLon = 0;
        for (double[] point : coordinates) {
            long lon = Math.round(point[0] * 1_000_000), lat = Math.round(point[1] * 1_000_000);
            append(text, lat - priorLat); append(text, lon - priorLon);
            priorLat = lat; priorLon = lon;
        }
        return text.toString();
    }
    private static void append(StringBuilder text, long signed) {
        long value = signed < 0 ? ~(signed << 1) : signed << 1;
        while (value >= 0x20) { text.append((char) ((0x20 | (value & 0x1f)) + 63)); value >>= 5; }
        text.append((char) (value + 63));
    }

    @AfterAll static void stop() throws Exception { PROVIDER.stop(0); Files.deleteIfExists(CATALOG); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        NativeAuthHttpTest.databaseProperties(properties);
        String origin = "http://127.0.0.1:" + PROVIDER.getAddress().getPort();
        properties.add("ROUTIQO_VALHALLA_ORIGIN", () -> origin);
        properties.add("ROUTIQO_PHOTON_ORIGIN", () -> origin);
        properties.add("ROUTIQO_LIVE_ANCHOR_CATALOG_PATH", CATALOG::toString);
    }

    @TestConfiguration static class Rates {
        @Bean @Qualifier("bindingRateFailure") AtomicBoolean failure() { return new AtomicBoolean(); }
        @Bean @Primary AuthRateGate fixedRates(JdbcTemplate jdbc,
                @Value("${ROUTIQO_AUTH_RATE_SECRET}") String secret,
                @Qualifier("bindingRateFailure") AtomicBoolean failure) {
            var delegate = new JdbcAuthRateGate(jdbc, secret,
                    Clock.fixed(Instant.parse("2026-09-24T10:00:00Z"), ZoneOffset.UTC));
            return (identity, category, limit) -> {
                if (failure.get() && category.startsWith("route-context-"))
                    throw new IllegalStateException("Fixture rate unavailable");
                return delegate.allow(identity, category, limit);
            };
        }
    }

    record Login(String account, String credential) {}
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthRateGate rates;
    @Autowired PresenceConsentService consents;
    @Autowired ApplicationContext context;
    @Autowired @Qualifier("bindingRateFailure") AtomicBoolean rateFailure;
    HttpClient client;

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM auth_rate_bucket");
        jdbc.update("DELETE FROM login_challenge");
        jdbc.update("DELETE FROM routiqo_account");
        CALLS.set(0); MODE = Mode.ROUTE; rateFailure.set(false);
        ENTERED = new CountDownLatch(0); RELEASE = new CountDownLatch(0);
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpResponse<String> send(String method, String path, byte[] body, Login login,
            List<String[]> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/native/" + path));
        if (login != null) {
            builder.header("Authorization", "Bearer " + login.credential());
            builder.header("X-Routiqo-Account", login.account());
        }
        for (var header : headers) builder.header(header[0], header[1]);
        if (method.equals("POST")) {
            if (headers.stream().noneMatch(header -> header[0].equalsIgnoreCase("Content-Type")))
                builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
        } else if (method.equals("GET")) builder.GET();
        else builder.method(method, HttpRequest.BodyPublishers.noBody());
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.headers().allValues("Access-Control-Allow-Origin")).isEmpty();
        return response;
    }
    HttpResponse<String> post(String path, String body, Login login) throws Exception {
        return send("POST", path, body.getBytes(StandardCharsets.UTF_8), login, List.of());
    }
    HttpResponse<String> get(String path, Login login) throws Exception {
        return send("GET", path, new byte[0], login, List.of());
    }
    Login login(boolean other) throws Exception {
        var challenge = post("auth/google/challenge", "{}", null);
        String id = JsonPath.read(challenge.body(), "$.id");
        String nonce = JsonPath.read(challenge.body(), "$.nonce");
        String binding = JsonPath.read(challenge.body(), "$.binding");
        var exchange = post("auth/google/exchange", NativeAuthHttpTest.exchangeBody(id, binding,
                other ? nonce + "-other" : nonce), null);
        assertThat(exchange.statusCode()).isEqualTo(200);
        return new Login(JsonPath.read(exchange.body(), "$.accountId"),
                JsonPath.read(exchange.body(), "$.credential"));
    }
    String start(Login actor) throws Exception {
        String journey = UUID.randomUUID().toString();
        assertThat(post("journeys", "{\"id\":\"" + journey + "\",\"kind\":\"trip\"}", actor)
                .statusCode()).isEqualTo(200);
        return journey;
    }
    static String path(String journey) { return "journeys/" + journey + "/route-context"; }
    static String body(String expected) {
        return body(expected, -0.02, 0.02);
    }
    static String body(String expected, double origin, double destination) {
        return "{\"mode\":\"driving\",\"origin\":[" + origin + ",0],\"destination\":["
                + destination + ",0],"
                + "\"alternativeIndex\":0,\"expectedContextId\":"
                + (expected == null ? "null" : "\"" + expected + "\"") + "}";
    }
    static void empty(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).isEmpty();
    }

    @Test void nativeOnlyCompositionBindsRecoversAndPreservesEmptyOutcomes() throws Exception {
        assertThat(context.getBeansOfType(NativeRouteContextController.class)).hasSize(1);
        Login owner = login(false);
        String journey = start(owner);
        assertThat(JsonPath.<Map<String, Object>>read(get(path(journey), owner).body(), "$").keySet())
                .containsOnly("context");
        assertThat(JsonPath.<Object>read(get(path(journey), owner).body(), "$.context")).isNull();
        empty(post(path(journey), body(null), owner), 409);
        assertThat(CALLS).hasValue(0);
        consents.submitIntent(UUID.fromString(owner.account()), UUID.fromString(journey), 0, true);
        var bound = post(path(journey), body(null), owner);
        assertThat(bound.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(bound.body(), "$.status")).isEqualTo("bound");
        String contextId = JsonPath.read(bound.body(), "$.context.contextId");
        assertThat(JsonPath.<String>read(bound.body(), "$.context.revision")).isEqualTo("0");
        assertThat(JsonPath.<List<?>>read(bound.body(), "$.context.anchorIds")).hasSize(1);
        assertThat(JsonPath.<Map<String, Object>>read(bound.body(), "$.context").keySet())
                .containsOnly("contextId", "revision", "anchorIds", "issuedAt", "expiresAt");
        assertThat(bound.body()).doesNotContain(owner.account(), journey, "geometry", "catalogVersion");
        assertThat(JsonPath.<String>read(get(path(journey), owner).body(), "$.context.contextId"))
                .isEqualTo(contextId);
        empty(post(path(journey), body(null), owner), 409);
        MODE = Mode.FAR;
        var noAnchors = post(path(journey), body(contextId, 0.48, 0.52), owner);
        assertThat(noAnchors.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(noAnchors.body(), "$.status")).isEqualTo("no_eligible_anchors");
        assertThat(JsonPath.<Object>read(noAnchors.body(), "$.context")).isNull();
        MODE = Mode.NO_ROUTE;
        var noRoute = post(path(journey), body(contextId), owner);
        assertThat(JsonPath.<String>read(noRoute.body(), "$.status")).isEqualTo("no_route");
        assertThat(JsonPath.<String>read(get(path(journey), owner).body(), "$.context.contextId"))
                .isEqualTo(contextId);
        assertThat(CALLS).hasValue(3);
    }

    @Test void ownerAuthStrictInputAndAmbientDenialsNeverReachProvider() throws Exception {
        Login owner = login(false), other = login(true);
        String journey = start(owner);
        for (String invalid : List.of("{}", "[]", "null", "{", body(null) + "{}",
                body(null).replace("\"alternativeIndex\":0", "\"alternativeIndex\":0.0"),
                body(null).replace("\"expectedContextId\":null", "\"expectedContextId\":\"00000000-0000-0000-0000-000000000000\""),
                body(null).replace("\"mode\":\"driving\"", "\"mode\":\"flying\""),
                body(null).replace("\"origin\":[-0.02,0]", "\"origin\":[\"-0.02\",0]"),
                body(null).replace("\"expectedContextId\":null", "\"expectedContextId\":null,\"extra\":1"),
                body(null).replace("\"mode\":\"driving\"", "\"mode\":\"driving\",\"mode\":\"driving\""))) {
            empty(post(path(journey), invalid, owner), 400);
        }
        empty(send("POST", path(journey), new byte[] {(byte) 0xc3, 0x28}, owner, List.of()), 400);
        empty(post(path(journey), "x".repeat(20 * 1024 + 1), owner), 413);
        empty(send("POST", path(journey), body(null).getBytes(StandardCharsets.UTF_8), owner,
                List.<String[]>of(new String[] {"Content-Type", "text/plain"})), 415);
        empty(get(path(journey), null), 401);
        empty(get(path(journey), other), 404);
        empty(post(path(journey), body(null), other), 404);
        empty(send("GET", path(journey), new byte[0], owner,
                List.<String[]>of(new String[] {"X-Routiqo-Account", UUID.randomUUID().toString()})), 401);
        for (String header : List.of("Cookie", "Origin", "Sec-Fetch-Site"))
            empty(send("GET", path(journey), new byte[0], owner,
                    List.<String[]>of(new String[] {header, "blocked"})), 403);
        empty(get(path(journey) + "?x=1", owner), 403);
        empty(get(path(journey) + "/extra", owner), 403);
        empty(send("DELETE", path(journey), new byte[0], owner, List.of()), 403);
        empty(get(path("00000000-0000-0000-0000-000000000000"), owner), 400);
        empty(get(path(journey.toUpperCase()), owner), 400);
        assertThat(CALLS).hasValue(0);
    }

    @Test void readAndBindUseSeparateSharedQuotaAndFailureSanitization() throws Exception {
        Login owner = login(false);
        String journey = start(owner);
        for (int index = 0; index < 59; index++)
            assertThat(rates.allow(owner.account(), "route-context-read-account", 60)).isTrue();
        assertThat(get(path(journey), owner).statusCode()).isEqualTo(200);
        var limited = get(path(journey), owner);
        empty(limited, 429);
        assertThat(limited.headers().firstValue("Retry-After")).contains("60");
        consents.submitIntent(UUID.fromString(owner.account()), UUID.fromString(journey), 0, true);
        for (int index = 0; index < 9; index++)
            assertThat(rates.allow(owner.account(), "route-binding-account", 10)).isTrue();
        var bound = post(path(journey), body(null), owner);
        assertThat(bound.statusCode()).isEqualTo(200);
        String current = JsonPath.read(bound.body(), "$.context.contextId");
        empty(post(path(journey), body(current), owner), 429);
        rateFailure.set(true);
        empty(get(path(journey), owner), 503);
        rateFailure.set(false);
        jdbc.update("DELETE FROM auth_rate_bucket");
        MODE = Mode.FAILURE;
        empty(post(path(journey), body(current), owner), 503);
        assertThat(JsonPath.<String>read(get(path(journey), owner).body(), "$.context.contextId"))
                .isEqualTo(current);
    }

    @Test void revokedConsentDuringProviderWorkCannotPublishAStaleContext() throws Exception {
        Login owner = login(false);
        String journey = start(owner);
        consents.submitIntent(UUID.fromString(owner.account()), UUID.fromString(journey), 0, true);
        MODE = Mode.BLOCKED;
        ENTERED = new CountDownLatch(1);
        RELEASE = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var request = executor.submit(() -> post(path(journey), body(null), owner));
            assertThat(ENTERED.await(5, TimeUnit.SECONDS)).isTrue();
            consents.submitIntent(UUID.fromString(owner.account()), UUID.fromString(journey), 1, false);
            RELEASE.countDown();
            empty(request.get(10, TimeUnit.SECONDS), 409);
        } finally { RELEASE.countDown(); }
        assertThat(JsonPath.<Object>read(get(path(journey), owner).body(), "$.context")).isNull();
    }

    @Test void completedAndExpiredContextReadsReturnNullWithoutRenewal() throws Exception {
        Login owner = login(false);
        String first = start(owner);
        consents.submitIntent(UUID.fromString(owner.account()), UUID.fromString(first), 0, true);
        assertThat(post(path(first), body(null), owner).statusCode()).isEqualTo(200);
        jdbc.update("""
                UPDATE live_route_context SET issued_at = now() - interval '2 seconds',
                    expires_at = now() - interval '1 second' WHERE actor_id = ?
                """, UUID.fromString(owner.account()));
        assertThat(JsonPath.<Object>read(get(path(first), owner).body(), "$.context")).isNull();
        assertThat(post("journeys/" + first + "/complete", "{}", owner).statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Object>read(get(path(first), owner).body(), "$.context")).isNull();
        empty(post(path(first), body(null), owner), 409);
    }
}
