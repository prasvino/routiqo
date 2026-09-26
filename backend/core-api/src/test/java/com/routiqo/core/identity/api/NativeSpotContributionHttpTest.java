package com.routiqo.core.identity.api;

import com.jayway.jsonpath.JsonPath;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.infrastructure.JdbcAuthRateGate;
import com.routiqo.core.spot.api.NativeSpotContributionController;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
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
    "ROUTIQO_SPOTS_CONTRIBUTIONS_ENABLED=true",
    "ROUTIQO_ROUTING_REGION_WEST=-1", "ROUTIQO_ROUTING_REGION_SOUTH=-1",
    "ROUTIQO_ROUTING_REGION_EAST=1", "ROUTIQO_ROUTING_REGION_NORTH=1",
    "ROUTIQO_VALHALLA_ORIGIN=http://127.0.0.1:18002", "ROUTIQO_PHOTON_ORIGIN=http://127.0.0.1:12322",
    "logging.level.org.springframework.web=DEBUG", "logging.level.org.springframework.security=DEBUG",
    "logging.level.com.routiqo=DEBUG"
})
@ActiveProfiles({"persistence", "google-auth", "native-auth", "routing"})
@Import({NativeAuthHttpTest.TestIdentity.class, NativeSpotContributionHttpTest.Rates.class})
@ExtendWith(OutputCaptureExtension.class)
class NativeSpotContributionHttpTest {
    static final String TOLL = NativeSpotHttpTest.FIRST;
    static final String BUS_STAND = NativeSpotHttpTest.SECOND;
    private static final Path CATALOG = catalog();

    private static Path catalog() {
        try {
            Path path = Files.createTempFile("routiqo-native-spot-contributions-", ".json");
            Files.writeString(path, NativeSpotHttpTest.catalogJson(), StandardCharsets.UTF_8);
            return path;
        } catch (Exception failure) { throw new IllegalStateException("Fixture unavailable", failure); }
    }

    @AfterAll static void clean() throws Exception { Files.deleteIfExists(CATALOG); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        NativeAuthHttpTest.databaseProperties(properties);
        properties.add("ROUTIQO_SPOT_CATALOG_PATH", CATALOG::toString);
    }

    @TestConfiguration static class Rates {
        @Bean @Primary AuthRateGate fixedRates(JdbcTemplate jdbc,
                @Value("${ROUTIQO_AUTH_RATE_SECRET}") String secret) {
            return new JdbcAuthRateGate(jdbc, secret,
                    Clock.fixed(Instant.parse("2026-11-05T06:30:00Z"), ZoneOffset.UTC));
        }
    }

    record Login(String account, String credential) {}
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;
    HttpClient client;

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM auth_rate_bucket");
        jdbc.update("DELETE FROM login_challenge");
        jdbc.update("DELETE FROM routiqo_account");
        jdbc.update("DELETE FROM spot_signal_group");
        jdbc.update("DELETE FROM spot_vote");
        jdbc.update("DELETE FROM spot_report_group");
        jdbc.update("DELETE FROM spot_report_evidence");
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpResponse<String> post(String path, String body, Login login, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/native/" + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (login != null) builder.header("Authorization", "Bearer " + login.credential())
                .header("X-Routiqo-Account", login.account());
        for (int index = 0; index < headers.length; index += 2) builder.header(headers[index], headers[index + 1]);
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        return response;
    }
    Login login(boolean other) throws Exception {
        var challenge = post("auth/google/challenge", "{}", null);
        String id = JsonPath.read(challenge.body(), "$.id");
        String nonce = JsonPath.read(challenge.body(), "$.nonce");
        String binding = JsonPath.read(challenge.body(), "$.binding");
        var exchange = post("auth/google/exchange", NativeAuthHttpTest.exchangeBody(id, binding,
                other ? nonce + "-other" : nonce), null);
        assertThat(exchange.statusCode()).isEqualTo(200);
        return new Login(JsonPath.read(exchange.body(), "$.accountId"), JsonPath.read(exchange.body(), "$.credential"));
    }
    String start(Login actor) throws Exception {
        String journey = UUID.randomUUID().toString();
        assertThat(post("journeys", "{\"id\":\"" + journey + "\",\"kind\":\"trip\"}", actor).statusCode())
                .isEqualTo(200);
        return journey;
    }
    static String now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(); }
    static String signal(String spot, String category, String value, String captured, String journey) {
        return "{\"clientKey\":\"" + UUID.randomUUID() + "\",\"spotId\":\"" + spot + "\",\"category\":\""
                + category + "\",\"value\":\"" + value + "\",\"capturedAt\":\"" + captured + "\",\"journeyId\":\""
                + journey + "\"}";
    }
    static String postBody(String spot, String type, String text, String captured, String journey) {
        return "{\"clientKey\":\"" + UUID.randomUUID() + "\",\"spotId\":\"" + spot + "\",\"type\":\"" + type
                + "\",\"text\":\"" + text + "\",\"capturedAt\":\"" + captured + "\",\"journeyId\":\"" + journey + "\"}";
    }
    static void empty(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).isEmpty();
    }

    @Test void contributeVoteDeleteAndReadBackWithoutAccountIdentifiers() throws Exception {
        assertThat(context.getBeansOfType(NativeSpotContributionController.class)).hasSize(1);
        Login author = login(false);
        Login other = login(true);
        String journey = start(author);
        String otherJourney = start(other);

        var signal = post("spots/signals", signal(TOLL, "traffic", "slow", now(), journey), author);
        assertThat(signal.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Map<String, Object>>read(signal.body(), "$").keySet())
                .containsExactlyInAnyOrder("ref", "status", "expiresAt");
        assertThat(JsonPath.<String>read(signal.body(), "$.status")).isEqualTo("active");

        var posted = post("spots/posts", postBody(TOLL, "traffic", "Lane 3 moving, others slow", now(), journey),
                author);
        assertThat(posted.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Map<String, Object>>read(posted.body(), "$").keySet())
                .containsExactlyInAnyOrder("ref", "status", "expiresAt", "alias");
        String ref = JsonPath.read(posted.body(), "$.ref");
        String alias = JsonPath.read(posted.body(), "$.alias");

        var read = post("spots/activity", "{\"spotIds\":[\"" + TOLL + "\"]}", other);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(read.body(), "$.spots[0].state")).isEqualTo("live");
        assertThat(JsonPath.<Map<String, Object>>read(read.body(), "$.spots[0]").keySet()).containsExactlyInAnyOrder(
                "id", "state", "alertIds", "signals", "posts", "postsTruncated", "highlights");
        assertThat(JsonPath.<Map<String, Object>>read(read.body(), "$.spots[0].signals[0]").keySet())
                .containsExactlyInAnyOrder("ref", "category", "value", "values", "latestAt", "stillTrue", "viewerVote");
        assertThat(JsonPath.<Map<String, Object>>read(read.body(), "$.spots[0].posts[0]").keySet())
                .containsExactlyInAnyOrder("ref", "alias", "text", "type", "capturedAt", "expiresAt", "stillTrue",
                        "viewerVote", "mine", "hidden");
        assertThat(JsonPath.<Boolean>read(read.body(), "$.spots[0].posts[0].mine")).isFalse();
        assertThat(JsonPath.<String>read(read.body(), "$.spots[0].posts[0].alias")).isEqualTo(alias);
        assertThat(read.body()).doesNotContain(author.account(), other.account(), journey, otherJourney);

        var vote = post("spots/items/" + ref + "/vote", "{\"vote\":\"still_true\"}", other);
        assertThat(vote.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(vote.body(), "$.stillTrue")).isEqualTo(1);
        empty(post("spots/items/" + ref + "/vote", "{\"vote\":\"still_true\"}", author), 403);
        empty(post("spots/items/" + ref + "/delete", "{}", other), 404);
        var deleted = post("spots/items/" + ref + "/delete", "{}", author);
        assertThat(JsonPath.<String>read(deleted.body(), "$.status")).isEqualTo("deleted");
        var after = post("spots/activity", "{\"spotIds\":[\"" + TOLL + "\"]}", other);
        assertThat(JsonPath.<List<Object>>read(after.body(), "$.spots[0].posts")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(after.body(), "$.spots[0].signals")).hasSize(1);
    }

    @Test void errorsAreEmptyBodiedAndSpecific() throws Exception {
        Login author = login(false);
        String journey = start(author);
        String body = signal(TOLL, "traffic", "slow", now(), journey);
        assertThat(post("spots/signals", body, author).statusCode()).isEqualTo(200);
        empty(post("spots/signals", body.replace("\"slow\"", "\"moving\""), author), 409);
        empty(post("spots/signals", signal(TOLL, "food", "good", now(), journey), author), 404);
        empty(post("spots/signals", signal(UUID.randomUUID().toString(), "traffic", "slow", now(), journey), author), 404);
        empty(post("spots/signals", signal(BUS_STAND, "queue", "under_5",
                Instant.now().minus(Duration.ofMinutes(61)).toString(), journey), author), 410);
        empty(post("spots/signals", signal(BUS_STAND, "queue", "under_5",
                Instant.now().plus(Duration.ofMinutes(5)).toString(), journey), author), 400);
        empty(post("spots/signals", signal(BUS_STAND, "queue", "under_5", "2026-11-05 06:30", journey), author), 400);
        empty(post("spots/signals", signal(BUS_STAND, "queue", "under_5", now(), UUID.randomUUID().toString()),
                author), 404);
        empty(post("spots/signals", "{\"clientKey\":\"x\"}", author), 400);
        empty(post("spots/posts", postBody(TOLL, "place", "Call 98765 43210 for rooms", now(), journey), author), 422);
        empty(post("spots/posts", postBody(TOLL, "place", "two\\nlines", now(), journey), author), 400);
        empty(post("spots/items/" + UUID.randomUUID() + "/vote", "{\"vote\":\"still_true\"}", author), 404);
        empty(post("spots/items/" + UUID.randomUUID() + "/vote", "{\"vote\":\"maybe\"}", author), 400);
        empty(post("spots/items/not-a-ref/vote", "{\"vote\":\"still_true\"}", author), 403);
        empty(post("spots/signals", body, new Login(author.account(), author.credential()),
                "X-Routiqo-Account", UUID.randomUUID().toString()), 401);
        // New accounts: 2 posts per 10 minutes.
        assertThat(post("spots/posts", postBody(TOLL, "traffic", "One", now(), journey), author).statusCode())
                .isEqualTo(200);
        assertThat(post("spots/posts", postBody(TOLL, "traffic", "Two", now(), journey), author).statusCode())
                .isEqualTo(200);
        var limited = post("spots/posts", postBody(TOLL, "traffic", "Three", now(), journey), author);
        empty(limited, 429);
        assertThat(limited.headers().firstValue("Retry-After")).contains("60");
    }

    @Test void reportAndBlockAnswerMinimallyAndLeaveNoAccountOrReasonInLogs(CapturedOutput output) throws Exception {
        Login author = login(false);
        Login other = login(true);
        String journey = start(author);
        start(other);
        var posted = post("spots/posts", postBody(TOLL, "place", "Buy cashews at lane 4", now(), journey), author);
        String ref = JsonPath.read(posted.body(), "$.ref");
        int before = output.getAll().length();

        String requestId = UUID.randomUUID().toString();
        String body = "{\"requestId\":\"" + requestId + "\",\"reason\":\"personal_data\"}";
        var reported = post("spots/items/" + ref + "/reports", body, other);
        assertThat(reported.statusCode()).isEqualTo(202);
        assertThat(JsonPath.<Map<String, Object>>read(reported.body(), "$").keySet())
                .containsExactlyInAnyOrder("receivedAt", "receiptExpiresAt");
        assertThat(post("spots/items/" + ref + "/reports", body, other).body()).isEqualTo(reported.body());
        empty(post("spots/items/" + ref + "/reports", body.replace("personal_data", "spam"), other), 409);
        empty(post("spots/items/" + ref + "/reports",
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"reason\":\"spam\"}", author), 403);
        empty(post("spots/items/" + ref + "/reports",
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"reason\":\"rude\"}", other), 400);
        empty(post("spots/items/" + UUID.randomUUID() + "/reports",
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"reason\":\"spam\"}", other), 404);
        empty(post("spots/items/" + ref + "/reports", body, null), 401);

        empty(post("spots/items/" + ref + "/block-author", "{}", other), 204);
        empty(post("spots/items/" + ref + "/block-author", "{}", other), 204);
        empty(post("spots/items/" + ref + "/block-author", "{}", author), 403);
        empty(post("spots/items/" + ref + "/block-author", "{\"x\":1}", other), 400);
        empty(post("spots/items/" + UUID.randomUUID() + "/block-author", "{}", other), 404);
        var hidden = post("spots/activity", "{\"spotIds\":[\"" + TOLL + "\"]}", other);
        assertThat(JsonPath.<List<Object>>read(hidden.body(), "$.spots[0].posts")).isEmpty();
        var visible = post("spots/activity", "{\"spotIds\":[\"" + TOLL + "\"]}", author);
        assertThat(JsonPath.<List<Object>>read(visible.body(), "$.spots[0].posts")).hasSize(1);

        String logged = output.getAll().substring(before);
        assertThat(logged).contains("/reports", "/block-author");
        assertThat(logged).doesNotContain(author.account(), other.account(), "personal_data", requestId, "cashews");
    }

    @Test void postTextAliasesAndSpotIdsNeverReachLogs(CapturedOutput output) throws Exception {
        Login author = login(false);
        String journey = start(author);
        int before = output.getAll().length();
        var posted = post("spots/posts", postBody(BUS_STAND, "place", "Unique-marker tea stall behind platform 4",
                now(), journey), author);
        assertThat(posted.statusCode()).isEqualTo(200);
        String alias = JsonPath.read(posted.body(), "$.alias");
        post("spots/activity", "{\"spotIds\":[\"" + BUS_STAND + "\"]}", author);
        String logged = output.getAll().substring(before);
        assertThat(logged).contains("/api/v1/native/spots/posts");
        assertThat(logged).doesNotContain("Unique-marker", alias, BUS_STAND, journey);
    }
}
