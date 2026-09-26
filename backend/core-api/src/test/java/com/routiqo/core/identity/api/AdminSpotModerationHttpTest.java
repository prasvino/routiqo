package com.routiqo.core.identity.api;

import com.jayway.jsonpath.JsonPath;
import com.routiqo.core.identity.application.GoogleIdentityVerifier;
import com.routiqo.core.moderation.infrastructure.AdminSessionService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0075 end to end: travellers post and report through the native API, moderators decide through
 * the admin API, and activity reads change at once. Reporter-free, author-free responses throughout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com",
    "ROUTIQO_ADMIN_GOOGLE_CLIENT_ID=admin-client.apps.googleusercontent.com",
    "ROUTIQO_WEB_ORIGIN=http://localhost:3000", "ROUTIQO_ADMIN_ORIGIN=http://localhost:3001",
    "ROUTIQO_AUTH_SECURE_COOKIES=false",
    "ROUTIQO_ADMIN_ENABLED=true", "ROUTIQO_SPOTS_ADMIN_ENABLED=true",
    "ROUTIQO_SPOTS_API_ENABLED=true", "ROUTIQO_SPOTS_CONTRIBUTIONS_ENABLED=true",
    "ROUTIQO_ROUTING_REGION_WEST=-1", "ROUTIQO_ROUTING_REGION_SOUTH=-1",
    "ROUTIQO_ROUTING_REGION_EAST=1", "ROUTIQO_ROUTING_REGION_NORTH=1",
    "ROUTIQO_VALHALLA_ORIGIN=http://127.0.0.1:18002", "ROUTIQO_PHOTON_ORIGIN=http://127.0.0.1:12322"
})
@ActiveProfiles({"persistence", "google-auth", "native-auth", "routing", "web-auth"})
@Import({NativeAuthHttpTest.TestIdentity.class, AdminSpotModerationHttpTest.AdminIdentity.class})
class AdminSpotModerationHttpTest {
    static final String TOLL = NativeSpotHttpTest.FIRST;
    static final String ADMIN_ORIGIN = "http://localhost:3001";
    private static final Path CATALOG = catalog();

    private static Path catalog() {
        try {
            Path path = Files.createTempFile("routiqo-admin-spot-moderation-", ".json");
            Files.writeString(path, NativeSpotHttpTest.catalogJson(), StandardCharsets.UTF_8);
            return path;
        } catch (Exception failure) { throw new IllegalStateException("Fixture unavailable", failure); }
    }
    @AfterAll static void clean() throws Exception { Files.deleteIfExists(CATALOG); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        NativeAuthHttpTest.databaseProperties(properties);
        properties.add("ROUTIQO_SPOT_CATALOG_PATH", CATALOG::toString);
    }

    /** Admin test token is "nonce|subject". */
    @TestConfiguration static class AdminIdentity {
        @Bean @Primary AdminSessionService fakeAdminSessionService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
            return new AdminSessionService(jdbc, manager, (token, nonce) -> {
                if (!token.startsWith(nonce + "|")) throw new SecurityException("Invalid test credential");
                return new GoogleIdentityVerifier.Identity("google", token.substring(nonce.length() + 1));
            });
        }
    }

    record Login(String account, String credential) {}
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
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

    // Native traveller side.
    HttpResponse<String> nativePost(String path, String body, Login login) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/native/" + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (login != null) builder.header("Authorization", "Bearer " + login.credential())
                .header("X-Routiqo-Account", login.account());
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    Login login(boolean other) throws Exception {
        var challenge = nativePost("auth/google/challenge", "{}", null);
        String id = JsonPath.read(challenge.body(), "$.id");
        String nonce = JsonPath.read(challenge.body(), "$.nonce");
        String binding = JsonPath.read(challenge.body(), "$.binding");
        var exchange = nativePost("auth/google/exchange", NativeAuthHttpTest.exchangeBody(id, binding,
                other ? nonce + "-other" : nonce), null);
        assertThat(exchange.statusCode()).isEqualTo(200);
        return new Login(JsonPath.read(exchange.body(), "$.accountId"), JsonPath.read(exchange.body(), "$.credential"));
    }
    String start(Login actor) throws Exception {
        String journey = UUID.randomUUID().toString();
        assertThat(nativePost("journeys", "{\"id\":\"" + journey + "\",\"kind\":\"trip\"}", actor).statusCode())
                .isEqualTo(200);
        return journey;
    }
    static String now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(); }
    String postPlace(Login author, String journey, String text) throws Exception {
        var posted = nativePost("spots/posts", "{\"clientKey\":\"" + UUID.randomUUID() + "\",\"spotId\":\"" + TOLL
                + "\",\"type\":\"place\",\"text\":\"" + text + "\",\"capturedAt\":\"" + now()
                + "\",\"journeyId\":\"" + journey + "\"}", author);
        assertThat(posted.statusCode()).isEqualTo(200);
        return JsonPath.read(posted.body(), "$.ref");
    }
    String signal(Login author, String journey) throws Exception {
        var signal = nativePost("spots/signals", "{\"clientKey\":\"" + UUID.randomUUID() + "\",\"spotId\":\"" + TOLL
                + "\",\"category\":\"traffic\",\"value\":\"slow\",\"capturedAt\":\"" + now()
                + "\",\"journeyId\":\"" + journey + "\"}", author);
        assertThat(signal.statusCode()).isEqualTo(200);
        return jdbc.queryForObject("SELECT group_ref FROM spot_signal WHERE ref = ?::uuid", String.class,
                JsonPath.<String>read(signal.body(), "$.ref"));
    }
    void report(Login reporter, String itemRef, String reason) throws Exception {
        assertThat(nativePost("spots/items/" + itemRef + "/reports",
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"reason\":\"" + reason + "\"}", reporter).statusCode())
                .isEqualTo(202);
    }
    String activity(Login viewer) throws Exception {
        var read = nativePost("spots/activity", "{\"spotIds\":[\"" + TOLL + "\"]}", viewer);
        assertThat(read.statusCode()).isEqualTo(200);
        return read.body();
    }

    // Admin side: one cookie jar per moderator.
    final class Moderator {
        final HttpClient browser = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        final UUID account = UUID.randomUUID();
        String csrf;
        Moderator(String... permissions) throws Exception {
            String subject = "moderator-" + account;
            jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)", account, subject);
            for (String permission : permissions) jdbc.update("""
                    INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
                    VALUES (?, ?, ?, ?)
                    """, account, permission, Timestamp.from(Instant.now().minusSeconds(10)),
                    Timestamp.from(Instant.now().plusSeconds(3600)));
            csrf = JsonPath.read(send("GET", "auth/csrf", "").body(), "$.token");
            var challenge = send("POST", "auth/google/challenge", "{}");
            String id = JsonPath.read(challenge.body(), "$.id"), nonce = JsonPath.read(challenge.body(), "$.nonce");
            assertThat(send("POST", "auth/google/exchange", "{\"challengeId\":\"" + id + "\",\"idToken\":\"" + nonce
                    + "|" + subject + "\"}").statusCode()).isEqualTo(200);
        }
        HttpResponse<String> send(String method, String path, String body) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/admin/" + path));
            if (method.equals("POST")) builder.header("Origin", ADMIN_ORIGIN).header("X-XSRF-TOKEN", csrf)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            else builder.GET();
            return browser.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> queue() throws Exception { return send("GET", "spots/reports", ""); }
        HttpResponse<String> decide(String reportRef, String action, UUID requestId, String reason) throws Exception {
            return send("POST", "spots/reports/" + reportRef + "/" + action,
                    "{\"requestId\":\"" + requestId + "\",\"reason\":\"" + reason + "\"}");
        }
    }

    @Test void queueIsUrgentFirstAndCarriesNoIdentifiers() throws Exception {
        Login author = login(false);
        Login reporter = login(true);
        String journey = start(author);
        start(reporter);
        String group = signal(author, journey);
        String post = postPlace(author, journey, "Buy cashews at lane 4");
        report(reporter, group, "false_alarm");
        report(reporter, post, "personal_data");

        var moderator = new Moderator("spots_review");
        var queue = moderator.queue();
        assertThat(queue.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(queue.body(), "$.items")).hasSize(2);
        assertThat(JsonPath.<String>read(queue.body(), "$.items[0].kind")).isEqualTo("post");
        assertThat(JsonPath.<Boolean>read(queue.body(), "$.items[0].urgent")).isTrue();
        assertThat(JsonPath.<String>read(queue.body(), "$.items[0].text")).isEqualTo("Buy cashews at lane 4");
        assertThat(JsonPath.<String>read(queue.body(), "$.items[0].state")).isEqualTo("active");
        assertThat(JsonPath.<Integer>read(queue.body(), "$.items[0].reports.personal_data")).isEqualTo(1);
        assertThat(JsonPath.<String>read(queue.body(), "$.items[1].kind")).isEqualTo("summary");
        assertThat(JsonPath.<String>read(queue.body(), "$.items[1].category")).isEqualTo("traffic");
        assertThat(JsonPath.<String>read(queue.body(), "$.items[1].value")).isEqualTo("slow");
        assertThat(JsonPath.<Map<String, Object>>read(queue.body(), "$.items[0]").keySet()).containsExactlyInAnyOrder(
                "reportRef", "kind", "urgent", "spotName", "spotNameTa", "text", "postType", "alias", "category",
                "value", "capturedAt", "expiresAt", "state", "reports", "stillTrue", "noLongerTrue", "openSince");
        assertThat(JsonPath.<Map<String, Object>>read(queue.body(), "$.items[0].reports").keySet())
                .containsExactlyInAnyOrder("unsafe", "abuse", "personal_data", "false_alarm", "spam");
        assertThat(queue.body()).doesNotContain(author.account(), reporter.account(), journey, post, group,
                moderator.account.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_moderation_read_audit WHERE operator_id = ?",
                Integer.class, moderator.account)).isEqualTo(1);
        assertThat(moderator.send("GET", "spots/reports?limit=5", "").statusCode()).isEqualTo(400);
        assertThat(moderator.send("GET", "spots/reports?cursor=bm90LWEtY3Vyc29y", "").statusCode()).isEqualTo(400);
        assertThat(new Moderator("spots_hide").queue().statusCode()).isEqualTo(403);
    }

    @Test void hideRemovesAPostAtOnceShowsItsAuthorAndRestoreBringsItBack() throws Exception {
        Login author = login(false);
        Login reporter = login(true);
        String journey = start(author);
        start(reporter);
        String post = postPlace(author, journey, "Rooms cheap near the toll");
        report(reporter, post, "spam");
        var moderator = new Moderator("spots_review", "spots_hide");
        String reportRef = JsonPath.read(moderator.queue().body(), "$.items[0].reportRef");

        UUID request = UUID.randomUUID();
        var hidden = moderator.decide(reportRef, "hide", request, "spam");
        assertThat(hidden.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(hidden.body(), "$.status")).isEqualTo("hidden");
        assertThat(JsonPath.<Boolean>read(moderator.decide(reportRef, "hide", request, "spam").body(), "$.replayed"))
                .isTrue();
        assertThat(moderator.decide(reportRef, "hide", request, "abuse").statusCode()).isEqualTo(409);
        assertThat(moderator.decide(reportRef, "hide", UUID.randomUUID(), "spam").statusCode()).isEqualTo(409);

        assertThat(JsonPath.<List<Object>>read(activity(reporter), "$.spots[0].posts")).isEmpty();
        String own = activity(author);
        assertThat(JsonPath.<Boolean>read(own, "$.spots[0].posts[0].hidden")).isTrue();
        assertThat(JsonPath.<Boolean>read(own, "$.spots[0].posts[0].mine")).isTrue();
        assertThat(nativePost("spots/items/" + post + "/vote", "{\"vote\":\"still_true\"}", reporter).statusCode())
                .isEqualTo(404);
        assertThat(nativePost("spots/items/" + post + "/reports",
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"reason\":\"spam\"}", reporter).statusCode())
                .isEqualTo(404);
        assertThat(JsonPath.<List<Object>>read(moderator.queue().body(), "$.items")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT not_upheld_at IS NULL FROM spot_report_evidence WHERE ref = ?::uuid",
                Boolean.class, post)).isTrue();

        assertThat(moderator.decide(reportRef, "restore", UUID.randomUUID(), "spam").statusCode()).isEqualTo(400);
        var restored = moderator.decide(reportRef, "restore", UUID.randomUUID(), "not_upheld");
        assertThat(JsonPath.<String>read(restored.body(), "$.status")).isEqualTo("restored");
        assertThat(JsonPath.<Boolean>read(activity(reporter), "$.spots[0].posts[0].hidden")).isFalse();
        assertThat(JsonPath.<Boolean>read(activity(author), "$.spots[0].posts[0].hidden")).isFalse();
        assertThat(moderator.decide(reportRef, "restore", UUID.randomUUID(), "not_upheld").statusCode()).isEqualTo(409);
        // Restored: the report was not upheld, so it no longer blocks a highlight and counts against the reporter.
        assertThat(jdbc.queryForObject("SELECT not_upheld_at IS NOT NULL FROM spot_report_evidence WHERE ref = ?::uuid",
                Boolean.class, post)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_reporter_not_upheld WHERE reporter_id = ?::uuid",
                Integer.class, reporter.account())).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT action FROM spot_moderation_action WHERE operator_id = ? ORDER BY occurred_at",
                String.class, moderator.account)).containsExactly("HIDE", "RESTORE");
    }

    @Test void dismissClosesUntilANewReportAndNeedsOnlyReview() throws Exception {
        Login author = login(false);
        Login reporter = login(true);
        String journey = start(author);
        start(reporter);
        String post = postPlace(author, journey, "Tea stall open all night");
        report(reporter, post, "abuse");
        var reviewer = new Moderator("spots_review");
        String reportRef = JsonPath.read(reviewer.queue().body(), "$.items[0].reportRef");
        assertThat(reviewer.decide(reportRef, "hide", UUID.randomUUID(), "abuse").statusCode()).isEqualTo(403);
        assertThat(reviewer.decide(reportRef, "dismiss", UUID.randomUUID(), "abuse").statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(reviewer.decide(reportRef, "dismiss", UUID.randomUUID(), "not_upheld").body(),
                "$.status")).isEqualTo("dismissed");
        assertThat(JsonPath.<List<Object>>read(reviewer.queue().body(), "$.items")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(activity(reporter), "$.spots[0].posts")).hasSize(1);
        assertThat(reviewer.decide(reportRef, "dismiss", UUID.randomUUID(), "not_upheld").statusCode()).isEqualTo(409);

        // A later report (simulated: a second reporter's count and sequence) reopens the same group.
        jdbc.update("""
                UPDATE spot_report_group SET abuse = abuse + 1, latest_sequence = latest_sequence + 1000,
                    latest = now(), expires_at = now() + INTERVAL '30 days' WHERE ref = ?::uuid
                """, reportRef);
        assertThat(JsonPath.<String>read(reviewer.queue().body(), "$.items[0].reportRef")).isEqualTo(reportRef);
    }

    @Test void hidingASummaryIncidentAndClearingSignals() throws Exception {
        Login author = login(false);
        Login reporter = login(true);
        String journey = start(author);
        start(reporter);
        String group = signal(author, journey);
        report(reporter, group, "false_alarm");
        var moderator = new Moderator("spots_review", "spots_hide");
        String reportRef = JsonPath.read(moderator.queue().body(), "$.items[0].reportRef");
        assertThat(JsonPath.<List<Object>>read(activity(reporter), "$.spots[0].signals")).hasSize(1);
        assertThat(moderator.decide(reportRef, "hide", UUID.randomUUID(), "false_alarm").statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(activity(reporter), "$.spots[0].signals")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(activity(author), "$.spots[0].signals")).isEmpty();
        assertThat(moderator.decide(reportRef, "restore", UUID.randomUUID(), "error_correction").statusCode())
                .isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(activity(reporter), "$.spots[0].signals")).hasSize(1);

        // Coordinated false signals: clear every current signal for that Spot and category.
        jdbc.update("""
                UPDATE spot_report_group SET false_alarm = false_alarm + 1, latest_sequence = latest_sequence + 1000,
                    latest = now(), expires_at = now() + INTERVAL '30 days' WHERE ref = ?::uuid
                """, reportRef);
        assertThat(moderator.decide(reportRef, "clear-signals", UUID.randomUUID(), "abuse").statusCode()).isEqualTo(400);
        var cleared = moderator.decide(reportRef, "clear-signals", UUID.randomUUID(), "false_alarm");
        assertThat(JsonPath.<String>read(cleared.body(), "$.status")).isEqualTo("cleared");
        assertThat(JsonPath.<List<Object>>read(activity(reporter), "$.spots[0].signals")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_signal WHERE state = 'ACTIVE'", Integer.class))
                .isZero();
    }

    @Test void groupsWhoseEvidenceIsGoneCloseAsUnavailable() throws Exception {
        Login author = login(false);
        Login reporter = login(true);
        String journey = start(author);
        start(reporter);
        String post = postPlace(author, journey, "Fuel pump closed");
        report(reporter, post, "unsafe");
        jdbc.update("DELETE FROM spot_post WHERE ref = ?::uuid", post);
        var moderator = new Moderator("spots_review", "spots_hide");
        assertThat(JsonPath.<List<Object>>read(moderator.queue().body(), "$.items")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT decision FROM spot_report_group", String.class))
                .isEqualTo("CLOSED_EVIDENCE_UNAVAILABLE");
    }

    @Test void decisionsAreStrictJsonUnderCsrfAndPerOperatorRateLimited() throws Exception {
        var moderator = new Moderator("spots_review", "spots_hide");
        String unknown = UUID.randomUUID().toString();
        assertThat(moderator.send("POST", "spots/reports/" + unknown + "/hide",
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"reason\":\"spam\",\"x\":1}").statusCode()).isEqualTo(400);
        assertThat(moderator.decide(unknown, "hide", UUID.randomUUID(), "spam").statusCode()).isEqualTo(404);
        var noCsrf = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/admin/spots/reports/"
                + unknown + "/hide")).header("Origin", ADMIN_ORIGIN).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"requestId\":\"" + UUID.randomUUID()
                        + "\",\"reason\":\"spam\"}")).build();
        assertThat(moderator.browser.send(noCsrf, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        for (int i = 0; i < 9; i++)
            assertThat(moderator.decide(unknown, "hide", UUID.randomUUID(), "spam").statusCode()).isEqualTo(404);
        var limited = moderator.decide(unknown, "hide", UUID.randomUUID(), "spam");
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).contains("60");
    }
}
