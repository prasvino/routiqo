package com.routiqo.core.journal.api;

import com.jayway.jsonpath.JsonPath;
import com.routiqo.core.identity.application.GoogleIdentityVerifier;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com",
    "ROUTIQO_WEB_ORIGIN=http://localhost:3000", "ROUTIQO_AUTH_SECURE_COOKIES=false"
})
@ActiveProfiles({"persistence", "google-auth", "web-auth"})
@Import(BrowserJournalHttpTest.TestIdentity.class)
class BrowserJournalHttpTest {
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
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
                if (!token.startsWith(nonce + ":")) throw new SecurityException("Synthetic test credential rejected");
                return new GoogleIdentityVerifier.Identity("google",
                        UUID.fromString(token.substring(nonce.length() + 1)).toString());
            };
        }
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void rateBuckets() { jdbc.update("DELETE FROM auth_rate_bucket"); }

    record Browser(HttpClient client, String csrf, String account) {}
    HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    HttpResponse<String> send(HttpClient client, String path, String body, String csrf, String origin,
            String account) throws Exception {
        return send(client, path, body, csrf, origin, account, body == null ? null : "application/json");
    }
    HttpResponse<String> send(HttpClient client, String path, String body, String csrf, String origin,
            String account, String contentType) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/" + path))
                .timeout(java.time.Duration.ofSeconds(10));
        if (body == null) builder.GET();
        else {
            if (contentType != null) builder.header("Content-Type", contentType);
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
        }
        if (csrf != null) builder.header("X-XSRF-TOKEN", csrf);
        if (origin != null) builder.header("Origin", origin);
        if (account != null) builder.header("X-Routiqo-Account", account);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> send(Browser browser, String path, String body) throws Exception {
        return send(browser.client(), path, body, browser.csrf(), "http://localhost:3000", browser.account());
    }
    Browser login() throws Exception {
        HttpClient client = client();
        String csrf = JsonPath.read(send(client, "auth/csrf", null, null, null, null).body(), "$.token");
        HttpResponse<String> challenge = send(client, "auth/google/challenge", "{}", csrf,
                "http://localhost:3000", null);
        String id = JsonPath.read(challenge.body(), "$.id");
        String nonce = JsonPath.read(challenge.body(), "$.nonce");
        HttpResponse<String> exchange = send(client, "auth/google/exchange",
                "{\"challengeId\":\"" + id + "\",\"idToken\":\"" + nonce + ":" + UUID.randomUUID() + "\"}",
                csrf, "http://localhost:3000", null);
        assertThat(exchange.statusCode()).isEqualTo(200);
        return new Browser(client, csrf, JsonPath.read(exchange.body(), "$.accountId"));
    }
    UUID journey(Browser owner, String kind, String status) {
        UUID id = UUID.randomUUID();
        Instant started = Instant.parse("2026-09-12T07:00:00Z");
        Instant completed = status.equals("COMPLETED") ? started.plusSeconds(120) : null;
        jdbc.update("INSERT INTO journey (id, owner_id, kind, status, started_at, completed_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, UUID.fromString(owner.account()), kind, status, java.sql.Timestamp.from(started),
                completed == null ? null : java.sql.Timestamp.from(completed));
        return id;
    }
    String save(String title, String notes, long version, UUID mutation) {
        return "{\"title\":\"" + title + "\",\"notes\":\"" + notes + "\",\"expectedVersion\":"
                + version + ",\"mutationId\":\"" + mutation + "\"}";
    }

    @Test void getIsPrivateDerivedAndEligibleOnly() throws Exception {
        assertThat(send(client(), "journeys/" + UUID.randomUUID() + "/journal", null, null, null, null).statusCode())
                .isEqualTo(401);
        Browser owner = login(); Browser stranger = login();
        UUID trip = journey(owner, "TRIP", "COMPLETED");
        HttpResponse<String> result = send(owner, "journeys/" + trip + "/journal", null);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(JsonPath.<String>read(result.body(), "$.journey.id")).isEqualTo(trip.toString());
        assertThat(JsonPath.<String>read(result.body(), "$.journey.kind")).isEqualTo("trip");
        assertThat(JsonPath.<String>read(result.body(), "$.journey.status")).isEqualTo("completed");
        assertThat(JsonPath.<String>read(result.body(), "$.annotation.title")).isEmpty();
        assertThat(JsonPath.<String>read(result.body(), "$.annotation.notes")).isEmpty();
        assertThat(JsonPath.<Integer>read(result.body(), "$.annotation.version")).isZero();
        assertThat(JsonPath.<Object>read(result.body(), "$.annotation.updatedAt")).isNull();
        assertThat(result.body()).doesNotContain("ownerId", owner.account(), stranger.account());
        assertThat(send(stranger, "journeys/" + trip + "/journal", null).statusCode()).isEqualTo(404);
        assertThat(send(owner, "journeys/" + UUID.randomUUID() + "/journal", null).statusCode()).isEqualTo(404);
        assertThat(send(owner, "journeys/" + journey(owner, "TRIP", "ACTIVE") + "/journal", null).statusCode()).isEqualTo(409);
        jdbc.update("UPDATE journey SET status = 'COMPLETED', completed_at = started_at WHERE owner_id = ? AND status = 'ACTIVE'",
                UUID.fromString(owner.account()));
        assertThat(send(owner, "journeys/" + journey(owner, "COMMUTE", "COMPLETED") + "/journal", null).statusCode()).isEqualTo(409);
    }

    @Test void postRequiresSessionAccountOriginCsrfJsonAndBoundedBody() throws Exception {
        Browser owner = login(); UUID trip = journey(owner, "TRIP", "COMPLETED");
        String path = "journeys/" + trip + "/journal";
        String body = save("title", "notes", 0, UUID.randomUUID());
        assertThat(send(client(), path, body, owner.csrf(), "http://localhost:3000", owner.account()).statusCode()).isEqualTo(403);
        assertThat(send(owner.client(), path, body, owner.csrf(), "http://localhost:3000", null).statusCode()).isEqualTo(401);
        assertThat(send(owner.client(), path, body, owner.csrf(), "http://localhost:3000", UUID.randomUUID().toString()).statusCode()).isEqualTo(401);
        assertThat(send(owner.client(), path, body, null, "http://localhost:3000", owner.account()).statusCode()).isEqualTo(403);
        assertThat(send(owner.client(), path, body, owner.csrf(), "https://attacker.invalid", owner.account()).statusCode()).isEqualTo(403);
        assertThat(send(owner.client(), path, body, owner.csrf(), "http://localhost:3000", owner.account(), "text/plain").statusCode()).isEqualTo(415);
        assertThat(send(owner.client(), path, "x".repeat(20 * 1024 + 1), owner.csrf(), "http://localhost:3000", owner.account()).statusCode()).isEqualTo(413);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journey_journal_annotation WHERE journey_id = ?", Long.class, trip)).isZero();
    }

    @Test void jsonTypesAndNonIntegralVersionsAreNeverCoerced() throws Exception {
        Browser owner = login(); UUID trip = journey(owner, "TRIP", "COMPLETED");
        String path = "journeys/" + trip + "/journal"; String mutation = UUID.randomUUID().toString();
        for (String body : java.util.List.of(
                "{\"title\":\"\",\"notes\":\"\",\"expectedVersion\":\"0\",\"mutationId\":\"" + mutation + "\"}",
                "{\"title\":\"\",\"notes\":\"\",\"expectedVersion\":true,\"mutationId\":\"" + mutation + "\"}",
                "{\"title\":\"\",\"notes\":\"\",\"expectedVersion\":0.5,\"mutationId\":\"" + mutation + "\"}",
                "{\"title\":\"\",\"notes\":\"\",\"expectedVersion\":-0.5,\"mutationId\":\"" + mutation + "\"}",
                "{\"title\":\"\",\"notes\":\"\",\"expectedVersion\":1e-1,\"mutationId\":\"" + mutation + "\"}",
                "{\"title\":\"\",\"notes\":\"\",\"expectedVersion\":0.999999999999999999999999999999,\"mutationId\":\"" + mutation + "\"}",
                "{\"title\":\"\",\"notes\":\"\",\"expectedVersion\":0e999999999,\"mutationId\":\"" + mutation + "\"}",
                "{\"title\":\"\",\"notes\":\"\",\"expectedVersion\":9007199254740991,\"mutationId\":\"" + mutation + "\"}",
                "{\"title\":\"\",\"notes\":\"\",\"expectedVersion\":9223372036854775808,\"mutationId\":\"" + mutation + "\"}",
                "{\"title\":17,\"notes\":\"\",\"expectedVersion\":0,\"mutationId\":\"" + mutation + "\"}",
                "{\"title\":true,\"notes\":\"\",\"expectedVersion\":0,\"mutationId\":\"" + mutation + "\"}",
                "{\"title\":{},\"notes\":\"\",\"expectedVersion\":0,\"mutationId\":\"" + mutation + "\"}",
                "{\"title\":\"\",\"notes\":17,\"expectedVersion\":0,\"mutationId\":\"" + mutation + "\"}",
                "{\"title\":\"\",\"notes\":false,\"expectedVersion\":0,\"mutationId\":\"" + mutation + "\"}",
                "{\"title\":\"\",\"notes\":[],\"expectedVersion\":0,\"mutationId\":\"" + mutation + "\"}",
                "{\"title\":\"\",\"notes\":\"\",\"expectedVersion\":0,\"mutationId\":17}",
                "{\"title\":\"first\",\"title\":\"second\",\"notes\":\"\",\"expectedVersion\":0,\"mutationId\":\"" + mutation + "\"}")) {
            assertThat(send(owner, path, body).statusCode()).as(body).isEqualTo(400);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM journey_journal_annotation WHERE journey_id = ?",
                    Long.class, trip)).isZero();
        }

        UUID decimalTrip = journey(owner, "TRIP", "COMPLETED");
        String decimal = "{\"title\":\"\",\"notes\":\"\",\"expectedVersion\":0.0,\"mutationId\":\""
                + UUID.randomUUID() + "\"}";
        assertThat(send(owner, "journeys/" + decimalTrip + "/journal", decimal).statusCode()).isEqualTo(200);
        UUID exponentTrip = journey(owner, "TRIP", "COMPLETED");
        String exponent = "{\"title\":\"\",\"notes\":\"\",\"expectedVersion\":0e5,\"mutationId\":\""
                + UUID.randomUUID() + "\"}";
        assertThat(send(owner, "journeys/" + exponentTrip + "/journal", exponent).statusCode()).isEqualTo(200);
    }

    @Test void invalidContentVersionsAndIdentifiersReturnBadRequestWithoutLeakingContent() throws Exception {
        Browser owner = login(); UUID trip = journey(owner, "TRIP", "COMPLETED");
        String path = "journeys/" + trip + "/journal"; UUID mutation = UUID.randomUUID();
        for (String body : java.util.List.of(
                "{}",
                "null",
                save("a".repeat(121), "", 0, mutation),
                save("", "n".repeat(4001), 0, mutation),
                save("bad\\nline", "", 0, mutation),
                save("", "bad\\u0000control", 0, mutation),
                save("bad\\ud800", "", 0, mutation),
                save("", "", -1, mutation),
                save("", "", 9_007_199_254_740_991L, mutation),
                save("", "", 0, mutation).replace(mutation.toString(), "not-a-uuid"),
                "{not-json}")) {
            HttpResponse<String> response = send(owner, path, body);
            assertThat(response.statusCode()).as(body.length() < 200 ? body : "long input").isEqualTo(400);
            assertThat(response.body()).doesNotContain("bad", "control", "not-a-uuid");
        }
        assertThat(send(owner, "journeys/not-a-uuid/journal", save("", "", 0, mutation)).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journey_journal_annotation WHERE journey_id = ?", Long.class, trip)).isZero();
    }

    @Test void writesUseCasReplayAndPersistentClear() throws Exception {
        Browser owner = login(); UUID trip = journey(owner, "TRIP", "COMPLETED");
        String path = "journeys/" + trip + "/journal"; UUID firstId = UUID.randomUUID();
        String firstBody = save(" My trip ", "line one\\nline two\\t", 0, firstId);
        HttpResponse<String> first = send(owner, path, firstBody);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(JsonPath.<String>read(first.body(), "$.annotation.title")).isEqualTo(" My trip ");
        assertThat(JsonPath.<String>read(first.body(), "$.annotation.notes")).isEqualTo("line one\nline two\t");
        assertThat(JsonPath.<Integer>read(first.body(), "$.annotation.version")).isEqualTo(1);
        String updatedAt = JsonPath.read(first.body(), "$.annotation.updatedAt");
        HttpResponse<String> replay = send(owner, path, firstBody);
        assertThat(JsonPath.<Integer>read(replay.body(), "$.annotation.version")).isEqualTo(1);
        assertThat(JsonPath.<String>read(replay.body(), "$.annotation.updatedAt")).isEqualTo(updatedAt);

        HttpResponse<String> reuse = send(owner, path, save("changed", "line one", 0, firstId));
        assertThat(reuse.statusCode()).isEqualTo(409);
        assertThat(reuse.body()).doesNotContain("changed", "line one");
        UUID secondId = UUID.randomUUID();
        assertThat(send(owner, path, save("Next", "", 1, secondId)).statusCode()).isEqualTo(200);
        assertThat(send(owner, path, firstBody).statusCode()).isEqualTo(409);
        HttpResponse<String> cleared = send(owner, path, save("", "", 2, UUID.randomUUID()));
        assertThat(JsonPath.<Integer>read(cleared.body(), "$.annotation.version")).isEqualTo(3);
        assertThat(JsonPath.<Object>read(cleared.body(), "$.annotation.updatedAt")).isNotNull();
        assertThat(JsonPath.<String>read(cleared.body(), "$.annotation.title")).isEmpty();
        assertThat(JsonPath.<Integer>read(send(owner, path, null).body(), "$.annotation.version")).isEqualTo(3);
    }

    @Test void writeRateIsTwentyPerAccountPerMinute() throws Exception {
        Browser owner = login(); UUID trip = journey(owner, "TRIP", "COMPLETED");
        String path = "journeys/" + trip + "/journal";
        for (int version = 0; version < 20; version++) {
            HttpResponse<String> response = send(owner, path, save("v" + version, "", version, UUID.randomUUID()));
            assertThat(response.statusCode()).as("write %s", version + 1).isEqualTo(200);
        }
        HttpResponse<String> limited = send(owner, path, save("blocked", "", 20, UUID.randomUUID()));
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).contains("60");
        assertThat(JsonPath.<Integer>read(send(owner, path, null).body(), "$.annotation.version")).isEqualTo(20);
    }
}
