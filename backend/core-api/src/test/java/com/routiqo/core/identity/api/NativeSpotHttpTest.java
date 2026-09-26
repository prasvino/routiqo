package com.routiqo.core.identity.api;

import com.jayway.jsonpath.JsonPath;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.infrastructure.JdbcAuthRateGate;
import com.routiqo.core.spot.api.NativeSpotController;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
    "ROUTIQO_SPOTS_API_ENABLED=true",
    "ROUTIQO_ROUTING_REGION_WEST=-1", "ROUTIQO_ROUTING_REGION_SOUTH=-1",
    "ROUTIQO_ROUTING_REGION_EAST=1", "ROUTIQO_ROUTING_REGION_NORTH=1",
    "ROUTIQO_VALHALLA_ORIGIN=http://127.0.0.1:18002", "ROUTIQO_PHOTON_ORIGIN=http://127.0.0.1:12322",
    "logging.level.org.springframework.web=DEBUG", "logging.level.org.springframework.security=DEBUG",
    "logging.level.com.routiqo=DEBUG"
})
@ActiveProfiles({"persistence", "google-auth", "native-auth", "routing"})
@Import({NativeAuthHttpTest.TestIdentity.class, NativeSpotHttpTest.Rates.class})
@ExtendWith(OutputCaptureExtension.class)
class NativeSpotHttpTest {
    static final String VERSION = "00000000-0000-4000-8000-0000000000aa";
    static final String ETAG = "\"" + VERSION + "\"";
    static final String FIRST = "00000000-0000-4000-8000-00000000a001";
    static final String SECOND = "00000000-0000-4000-8000-00000000a002";
    static final String UNKNOWN = "00000000-0000-4000-8000-00000000a0ff";
    private static final Path CATALOG = catalog();

    static String catalogJson() {
        return """
                {"schema":"routiqo-spots/1","version":"%s",
                 "corridors":[{"id":"gst-trunk","name":"Chennai – Trichy (GST Road)"}],
                 "spots":[
                  {"id":"%s","name":"Synthetic Toll","nameTa":"செயற்கை சுங்கச்சாவடி","kind":"toll",
                   "longitude":0.1,"latitude":0.2,"district":"chengalpattu","corridors":["gst-trunk"],
                   "categories":["traffic","queue"],
                   "provenance":{"curator":"Private Curator Marker","source":"field_visit","reviewedAt":"2026-09-20"}},
                  {"id":"%s","name":"Synthetic Bus Stand","nameTa":"செயற்கை பேருந்து நிலையம்","kind":"bus_stand",
                   "longitude":-0.1,"latitude":-0.2,"district":"tiruchirappalli","corridors":["gst-trunk"],
                   "categories":["queue"],
                   "provenance":{"curator":"Private Curator Marker","source":"osm","reviewedAt":"2026-09-21"}}]}
                """.formatted(VERSION, FIRST, SECOND);
    }

    private static Path catalog() {
        try {
            Path path = Files.createTempFile("routiqo-native-spots-", ".json");
            Files.writeString(path, catalogJson(), StandardCharsets.UTF_8);
            return path;
        } catch (Exception failure) { throw new IllegalStateException("Fixture unavailable", failure); }
    }

    @AfterAll static void clean() throws Exception { Files.deleteIfExists(CATALOG); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        NativeAuthHttpTest.databaseProperties(properties);
        properties.add("ROUTIQO_SPOT_CATALOG_PATH", CATALOG::toString);
    }

    @TestConfiguration static class Rates {
        @Bean @Qualifier("spotRateFailure") AtomicBoolean failure() { return new AtomicBoolean(); }
        @Bean @Primary AuthRateGate fixedRates(JdbcTemplate jdbc,
                @Value("${ROUTIQO_AUTH_RATE_SECRET}") String secret,
                @Qualifier("spotRateFailure") AtomicBoolean failure) {
            var delegate = new JdbcAuthRateGate(jdbc, secret,
                    Clock.fixed(Instant.parse("2026-11-05T06:30:00Z"), ZoneOffset.UTC));
            return (identity, category, limit) -> {
                if (failure.get() && category.startsWith("spot-"))
                    throw new IllegalStateException("Fixture rate unavailable");
                return delegate.allow(identity, category, limit);
            };
        }
    }

    record Login(String account, String credential) {}
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;
    @Autowired @Qualifier("spotRateFailure") AtomicBoolean rateFailure;
    HttpClient client;

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM auth_rate_bucket");
        jdbc.update("DELETE FROM login_challenge");
        jdbc.update("DELETE FROM routiqo_account");
        rateFailure.set(false);
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpResponse<String> send(String method, String path, String body, Login login, List<String[]> headers)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/native/" + path));
        if (login != null) {
            builder.header("Authorization", "Bearer " + login.credential());
            builder.header("X-Routiqo-Account", login.account());
        }
        for (var header : headers) builder.header(header[0], header[1]);
        if (method.equals("POST")) {
            if (headers.stream().noneMatch(header -> header[0].equalsIgnoreCase("Content-Type")))
                builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
        } else builder.GET();
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.headers().allValues("Access-Control-Allow-Origin")).isEmpty();
        return response;
    }
    HttpResponse<String> post(String path, String body, Login login) throws Exception {
        return send("POST", path, body, login, List.of());
    }
    HttpResponse<String> get(String path, Login login, String... ifNoneMatch) throws Exception {
        List<String[]> headers = ifNoneMatch.length == 0 ? List.of()
                : List.<String[]>of(new String[] {"If-None-Match", ifNoneMatch[0]});
        return send("GET", path, "", login, headers);
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
    static String ids(String... ids) {
        return "{\"spotIds\":[" + String.join(",", List.of(ids).stream().map(id -> "\"" + id + "\"").toList())
                + "]}";
    }
    static void empty(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).isEmpty();
    }

    @Test void catalogIsPublicReferenceDataWithStrictEtagRevalidation() throws Exception {
        assertThat(context.getBeansOfType(NativeSpotController.class)).hasSize(1);
        Login owner = login(false);
        var response = get("spots/catalog", owner);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("ETag")).contains(ETAG);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(JsonPath.<Map<String, Object>>read(response.body(), "$").keySet())
                .containsExactly("version", "corridors", "spots");
        assertThat(JsonPath.<String>read(response.body(), "$.version")).isEqualTo(VERSION);
        assertThat(JsonPath.<Map<String, Object>>read(response.body(), "$.spots[0]").keySet())
                .containsExactlyInAnyOrder("id", "name", "nameTa", "kind", "longitude", "latitude",
                        "district", "corridors", "categories");
        assertThat(JsonPath.<String>read(response.body(), "$.spots[0].nameTa")).isEqualTo("செயற்கை சுங்கச்சாவடி");
        assertThat(JsonPath.<String>read(response.body(), "$.spots[1].kind")).isEqualTo("bus_stand");
        assertThat(JsonPath.<List<String>>read(response.body(), "$.spots[0].categories"))
                .containsExactly("traffic", "queue");
        assertThat(response.body()).doesNotContain("provenance", "Private Curator Marker", "field_visit",
                "reviewedAt", "2026-09-20", owner.account());

        for (String tag : List.of(ETAG, "W/" + ETAG, "*", "\"other\", " + ETAG)) {
            var current = get("spots/catalog", owner, tag);
            empty(current, 304);
            assertThat(current.headers().firstValue("ETag")).contains(ETAG);
        }
        for (String tag : List.of("\"00000000-0000-4000-8000-0000000000bb\"", VERSION)) {
            var changed = get("spots/catalog", owner, tag);
            assertThat(changed.statusCode()).isEqualTo(200);
            assertThat(changed.body()).isEqualTo(response.body());
        }
    }

    @Test void bothLeavesRequireBearerAndMatchingAccountAndExactMethods() throws Exception {
        Login owner = login(false);
        Login other = login(true);
        start(owner);
        for (var request : List.of(new String[] {"GET", "spots/catalog"}, new String[] {"POST", "spots/activity"})) {
            empty(send(request[0], request[1], ids(FIRST), null, List.of()), 401);
            empty(send(request[0], request[1], ids(FIRST), null, List.<String[]>of(
                    new String[] {"Authorization", "Bearer " + owner.credential()})), 401);
            empty(send(request[0], request[1], ids(FIRST), null, List.<String[]>of(
                    new String[] {"Authorization", "Bearer " + owner.credential()},
                    new String[] {"X-Routiqo-Account", other.account()})), 401);
            empty(send(request[0], request[1], ids(FIRST), null, List.<String[]>of(
                    new String[] {"Authorization", "Bearer " + "x".repeat(43)},
                    new String[] {"X-Routiqo-Account", owner.account()})), 401);
        }
        empty(post("spots/catalog", "{}", owner), 403);
        empty(get("spots/activity", owner), 403);
        empty(get("spots", owner), 403);
        empty(get("spots/catalog?version=1", owner), 403);
        empty(send("GET", "spots/catalog", "", owner, List.<String[]>of(
                new String[] {"Origin", "https://example.com"})), 403);
    }

    @Test void activityRequiresAnActiveJourneyAndReturnsQuietKnownSpotsOnly() throws Exception {
        Login owner = login(false);
        empty(post("spots/activity", ids(FIRST), owner), 409);
        String journey = start(owner);

        var response = post("spots/activity", ids(FIRST, SECOND, UNKNOWN), owner);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(JsonPath.<Map<String, Object>>read(response.body(), "$").keySet())
                .containsExactly("serverTime", "catalogVersion", "spots", "alerts");
        assertThat(JsonPath.<String>read(response.body(), "$.catalogVersion")).isEqualTo(VERSION);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.spots[*].id")).containsExactly(FIRST, SECOND);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.spots[*].state")).containsOnly("quiet");
        assertThat(JsonPath.<List<Object>>read(response.body(), "$.spots[0].alertIds")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(response.body(), "$.alerts")).isEmpty();
        assertThat(response.body()).doesNotContain(UNKNOWN, owner.account(), journey);
        Instant.parse(JsonPath.read(response.body(), "$.serverTime"));

        var unknownOnly = post("spots/activity", ids(UNKNOWN), owner);
        assertThat(JsonPath.<List<Object>>read(unknownOnly.body(), "$.spots")).isEmpty();

        assertThat(post("journeys/" + journey + "/complete", "{}", owner).statusCode()).isEqualTo(200);
        empty(post("spots/activity", ids(FIRST), owner), 409);
        empty(post("spots/activity", ids(FIRST), login(true)), 409);
    }

    @Test void activityAcceptsOnlyTheExactBoundedSortedBody() throws Exception {
        Login owner = login(false);
        start(owner);
        var tooMany = new String[21];
        for (int index = 0; index < tooMany.length; index++)
            tooMany[index] = String.format("00000000-0000-4000-8000-%012x", index + 1);
        for (String invalid : List.of(
                "{}", "[]", "null", "{\"spotIds\":[]}", ids(SECOND, FIRST), ids(FIRST, FIRST),
                ids(FIRST.toUpperCase()), ids("00000000-0000-0000-0000-000000000000"), ids("not-a-uuid"),
                ids(tooMany), "{\"spotIds\":[\"" + FIRST + "\"],\"journeyId\":\"" + FIRST + "\"}",
                "{\"spotIds\":\"" + FIRST + "\"}", "{\"spotIds\":[1]}", ids(FIRST) + "{}",
                "{\"spotIds\":[\"" + FIRST + "\"],\"spotIds\":[\"" + SECOND + "\"]}"))
            empty(post("spots/activity", invalid, owner), 400);
        var twenty = new String[20];
        System.arraycopy(tooMany, 0, twenty, 0, 20);
        assertThat(post("spots/activity", ids(twenty), owner).statusCode()).isEqualTo(200);
        empty(send("POST", "spots/activity", ids(FIRST), owner,
                List.<String[]>of(new String[] {"Content-Type", "text/plain"})), 415);
        empty(post("spots/activity", "{\"spotIds\":[\"" + FIRST + "\"]}" + " ".repeat(21 * 1024), owner), 413);
    }

    @Test void accountRateGatesAreSeparateAndAnswer429WithRetryAfter() throws Exception {
        Login owner = login(false);
        start(owner);
        for (int index = 0; index < 10; index++)
            assertThat(get("spots/catalog", owner, ETAG).statusCode()).isEqualTo(304);
        var limited = get("spots/catalog", owner);
        empty(limited, 429);
        assertThat(limited.headers().firstValue("Retry-After")).contains("60");

        for (int index = 0; index < 20; index++)
            assertThat(post("spots/activity", ids(FIRST), owner).statusCode()).isEqualTo(200);
        var activityLimited = post("spots/activity", ids(FIRST), owner);
        empty(activityLimited, 429);
        assertThat(activityLimited.headers().firstValue("Retry-After")).contains("60");
        assertThat(get("spots/catalog", login(true)).statusCode()).isEqualTo(200);
    }

    @Test void contributionPathsStayForbiddenWithoutTheContributionFlag() throws Exception {
        Login owner = login(false);
        String journey = start(owner);
        for (String path : List.of("spots/signals", "spots/posts", "spots/items/" + UUID.randomUUID() + "/vote",
                "spots/items/" + UUID.randomUUID() + "/delete", "spots/items/" + UUID.randomUUID() + "/reports",
                "spots/items/" + UUID.randomUUID() + "/block-author"))
            empty(post(path, "{\"journeyId\":\"" + journey + "\"}", owner), 403);
    }

    @Test void unavailableRateAuthorityFailsClosed() throws Exception {
        Login owner = login(false);
        start(owner);
        rateFailure.set(true);
        empty(get("spots/catalog", owner), 503);
        empty(post("spots/activity", ids(FIRST), owner), 503);
    }

    @Test void requestedSpotIdsNeverReachLogsEvenAtDebug(CapturedOutput output) throws Exception {
        Login owner = login(false);
        start(owner);
        int before = output.getAll().length();
        assertThat(post("spots/activity", ids(FIRST, SECOND, UNKNOWN), owner).statusCode()).isEqualTo(200);
        empty(post("spots/activity", ids(SECOND, UNKNOWN, FIRST), owner), 400);
        String logged = output.getAll().substring(before);
        assertThat(logged).contains("/api/v1/native/spots/activity");
        assertThat(logged).doesNotContain(FIRST, SECOND, UNKNOWN, "spotIds");
    }
}
