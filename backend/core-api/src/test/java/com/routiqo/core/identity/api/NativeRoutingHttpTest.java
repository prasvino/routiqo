package com.routiqo.core.identity.api;

import com.jayway.jsonpath.JsonPath;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.infrastructure.JdbcAuthRateGate;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
    "ROUTIQO_NATIVE_ROUTING_API_ENABLED=true"
})
@ActiveProfiles({"persistence", "google-auth", "native-auth", "routing"})
@Import({NativeAuthHttpTest.TestIdentity.class, NativeRoutingHttpTest.ControlledRates.class})
class NativeRoutingHttpTest {
    private static final AtomicInteger PLACES = new AtomicInteger();
    private static final AtomicInteger ROUTES = new AtomicInteger();
    private static final AtomicBoolean PROVIDER_FAILURE = new AtomicBoolean();
    private static final HttpServer PROVIDER = provider();

    private static HttpServer provider() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api", exchange -> {
                PLACES.incrementAndGet();
                reply(exchange, PROVIDER_FAILURE.get() ? 500 : 200,
                        "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\","
                        + "\"properties\":{\"osm_type\":\"N\",\"osm_id\":123,"
                        + "\"name\":\"Marina Beach\",\"city\":\"Chennai\"},"
                        + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[80.2824,13.0499]}}]}");
            });
            server.createContext("/route", exchange -> {
                ROUTES.incrementAndGet();
                reply(exchange, PROVIDER_FAILURE.get() ? 500 : 200, routeFixture());
            });
            server.start();
            return server;
        } catch (Exception error) { throw new IllegalStateException("Local provider fixture unavailable"); }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static String routeFixture() {
        String shape = encode(new double[][] {{80, 13}, {79, 12}});
        return "{\"trip\":{\"locations\":[{\"type\":\"break\"},{\"type\":\"break\"}],"
                + "\"legs\":[{\"maneuvers\":[{\"type\":1,\"instruction\":\"Continue to destination\","
                + "\"length\":1,\"time\":60,\"begin_shape_index\":0,\"end_shape_index\":1}],"
                + "\"summary\":{\"length\":1,\"time\":60},\"shape\":\"" + shape + "\"}],"
                + "\"summary\":{\"length\":1,\"time\":60},\"status\":0,"
                + "\"units\":\"kilometers\",\"language\":\"en-US\"}}";
    }

    private static String encode(double[][] points) {
        StringBuilder text = new StringBuilder();
        long previousLatitude = 0, previousLongitude = 0;
        for (double[] point : points) {
            long longitude = Math.round(point[0] * 1_000_000), latitude = Math.round(point[1] * 1_000_000);
            append(text, latitude - previousLatitude);
            append(text, longitude - previousLongitude);
            previousLatitude = latitude; previousLongitude = longitude;
        }
        return text.toString();
    }

    private static void append(StringBuilder text, long signed) {
        long value = signed < 0 ? ~(signed << 1) : signed << 1;
        while (value >= 0x20) {
            text.append((char) ((0x20 | (value & 0x1f)) + 63));
            value >>= 5;
        }
        text.append((char) (value + 63));
    }

    @AfterAll static void stopProvider() { PROVIDER.stop(0); }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        NativeAuthHttpTest.databaseProperties(properties);
        String origin = "http://127.0.0.1:" + PROVIDER.getAddress().getPort();
        properties.add("ROUTIQO_VALHALLA_ORIGIN", () -> origin);
        properties.add("ROUTIQO_PHOTON_ORIGIN", () -> origin);
        properties.add("ROUTIQO_ROUTING_REGION_WEST", () -> "78");
        properties.add("ROUTIQO_ROUTING_REGION_SOUTH", () -> "11");
        properties.add("ROUTIQO_ROUTING_REGION_EAST", () -> "81");
        properties.add("ROUTIQO_ROUTING_REGION_NORTH", () -> "14");
    }

    @TestConfiguration static class ControlledRates {
        @Bean @Qualifier("nativeRoutingRateFailure") AtomicBoolean rateFailure() { return new AtomicBoolean(); }
        @Bean @Primary AuthRateGate rates(JdbcTemplate jdbc,
                @Value("${ROUTIQO_AUTH_RATE_SECRET}") String secret,
                @Qualifier("nativeRoutingRateFailure") AtomicBoolean failure) {
            var delegate = new JdbcAuthRateGate(jdbc, secret,
                    Clock.fixed(Instant.parse("2026-09-24T10:00:00Z"), ZoneOffset.UTC));
            return (identity, category, limit) -> {
                if (failure.get() && (category.equals("routing-account")
                        || category.equals("place-search-account"))) throw new IllegalStateException("Rate unavailable");
                return delegate.allow(identity, category, limit);
            };
        }
    }

    record Login(String account, String credential) {}
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthRateGate rates;
    @Autowired @Qualifier("nativeRoutingRateFailure") AtomicBoolean rateFailure;
    HttpClient client;

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM auth_rate_bucket");
        jdbc.update("DELETE FROM login_challenge");
        jdbc.update("DELETE FROM routiqo_account");
        PLACES.set(0); ROUTES.set(0); PROVIDER_FAILURE.set(false); rateFailure.set(false);
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpResponse<String> send(String method, String path, byte[] body, Login actor, List<String[]> extra)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/native/" + path));
        if (actor != null) {
            builder.header("Authorization", "Bearer " + actor.credential());
            builder.header("X-Routiqo-Account", actor.account());
        }
        for (var header : extra) builder.header(header[0], header[1]);
        if (method.equals("POST")) {
            if (extra.stream().noneMatch(header -> header[0].equalsIgnoreCase("Content-Type")))
                builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
        } else if (method.equals("GET")) builder.GET();
        else builder.method(method, HttpRequest.BodyPublishers.noBody());
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.headers().allValues("Access-Control-Allow-Origin")).isEmpty();
        assertThat(response.headers().allValues("Location")).isEmpty();
        return response;
    }

    HttpResponse<String> post(String path, String body, Login actor) throws Exception {
        return send("POST", path, body.getBytes(StandardCharsets.UTF_8), actor, List.of());
    }

    Login login(boolean other) throws Exception {
        var challenge = post("auth/google/challenge", "{}", null);
        assertThat(challenge.statusCode()).isEqualTo(200);
        String id = JsonPath.read(challenge.body(), "$.id");
        String nonce = JsonPath.read(challenge.body(), "$.nonce");
        String binding = JsonPath.read(challenge.body(), "$.binding");
        var exchange = post("auth/google/exchange", NativeAuthHttpTest.exchangeBody(id, binding,
                other ? nonce + "-other" : nonce), null);
        assertThat(exchange.statusCode()).isEqualTo(200);
        return new Login(JsonPath.read(exchange.body(), "$.accountId"),
                JsonPath.read(exchange.body(), "$.credential"));
    }

    static void empty(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).isEmpty();
    }

    static String route() {
        return "{\"mode\":\"driving\",\"origin\":[80,13],\"destination\":[79,12]}";
    }

    @Test void realConfiguredProvidersReturnOnlyNormalizedResultsAfterNativeAuth() throws Exception {
        Login owner = login(false);
        var places = post("routes/places", "{\"query\":\"Marina Beach\"}", owner);
        assertThat(places.statusCode()).isEqualTo(200);
        assertThat(places.body()).contains("\"provider\":\"photon\"", "\"id\":\"photon:n:123\"",
                "Marina Beach", "\"coordinate\":[80.2824,13.0499]", "\"attribution\":");
        assertThat(places.body()).doesNotContain(owner.account(), "osm_type", "osm_id");
        var result = post("routes", route(), owner);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).contains("\"provider\":\"valhalla\"", "Continue to destination",
                "\"geometry\":[[80.0,13.0],[79.0,12.0]]", "\"distanceMetres\":1000.0",
                "\"calculatedAt\":");
        assertThat(result.body()).doesNotContain(owner.account(), "costing", "locations");
        assertThat(PLACES).hasValue(1);
        assertThat(ROUTES).hasValue(1);
    }

    @Test void malformedInputAndAmbientTransportNeverReachProviders() throws Exception {
        Login owner = login(false);
        for (String invalid : List.of("{}", "[]", "null", "{", "{\"query\":4}",
                "{\"query\":\"a\"}", "{\"query\":\"Beach\",\"extra\":1}",
                "{\"query\":\"Beach\",\"query\":\"Again\"}",
                "{\"query\":\"Beach\"}{}")) empty(post("routes/places", invalid, owner), 400);
        for (String invalid : List.of("{}", "[]", "null", "{", "{\"mode\":\"flying\",\"origin\":[80,13],\"destination\":[79,12]}",
                "{\"mode\":\"driving\",\"origin\":[80],\"destination\":[79,12]}",
                "{\"mode\":\"driving\",\"origin\":[80,\"13\"],\"destination\":[79,12]}",
                "{\"mode\":\"driving\",\"origin\":[80,13],\"destination\":[80,13]}",
                "{\"mode\":\"driving\",\"origin\":[181,13],\"destination\":[79,12]}",
                "{\"mode\":\"driving\",\"origin\":[80,13],\"destination\":[79,12],\"extra\":1}",
                "{\"mode\":\"driving\",\"origin\":[80,13],\"destination\":[79,12],\"mode\":\"driving\"}",
                route() + "{}")) empty(post("routes", invalid, owner), 400);
        empty(send("POST", "routes", new byte[] {'{', (byte) 0xc3, (byte) 0x28, '}'}, owner, List.of()), 400);
        empty(post("routes?x=1", route(), owner), 403);
        empty(send("POST", "routes", route().getBytes(StandardCharsets.UTF_8), owner,
                List.<String[]>of(new String[] {"Cookie", "routiqo_session=forbidden"})), 403);
        empty(send("POST", "routes", route().getBytes(StandardCharsets.UTF_8), owner,
                List.<String[]>of(new String[] {"Origin", "https://attacker.example"})), 403);
        empty(send("POST", "routes", route().getBytes(StandardCharsets.UTF_8), owner,
                List.<String[]>of(new String[] {"Sec-Fetch-Site", "cross-site"})), 403);
        empty(send("POST", "routes", route().getBytes(StandardCharsets.UTF_8), owner,
                List.<String[]>of(new String[] {"X-Routiqo-Account", UUID.randomUUID().toString()})), 401);
        empty(send("POST", "routes", route().getBytes(StandardCharsets.UTF_8), owner,
                List.<String[]>of(new String[] {"Content-Type", "text/plain"})), 415);
        empty(post("routes", "x".repeat(20 * 1024 + 1), owner), 413);
        empty(send("GET", "routes", new byte[0], owner, List.of()), 403);
        empty(send("DELETE", "routes/places", new byte[0], owner, List.of()), 403);
        empty(send("POST", "routes/other", new byte[0], owner, List.of()), 403);
        empty(post("routes", route(), null), 401);
        assertThat(PLACES).hasValue(0);
        assertThat(ROUTES).hasValue(0);
    }

    @Test void coverageQuotaAndProviderFailuresAreBodylessAndSeparated() throws Exception {
        Login owner = login(false);
        empty(post("routes", "{\"mode\":\"driving\",\"origin\":[80,13],\"destination\":[82,12]}", owner), 422);
        assertThat(ROUTES).hasValue(0);
        for (int index = 0; index < 19; index++)
            assertThat(rates.allow(owner.account(), "place-search-account", 20)).isTrue();
        assertThat(post("routes/places", "{\"query\":\"Marina Beach\"}", owner).statusCode()).isEqualTo(200);
        var limited = post("routes/places", "{\"query\":\"Marina Beach\"}", owner);
        empty(limited, 429);
        assertThat(limited.headers().firstValue("Retry-After")).contains("60");
        assertThat(post("routes", route(), owner).statusCode()).isEqualTo(200);
        PROVIDER_FAILURE.set(true);
        empty(post("routes", route(), owner), 503);
        rateFailure.set(true);
        empty(post("routes", route(), owner), 503);
    }
}
