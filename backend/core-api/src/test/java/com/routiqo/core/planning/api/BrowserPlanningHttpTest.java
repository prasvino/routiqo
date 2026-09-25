package com.routiqo.core.planning.api;

import com.jayway.jsonpath.JsonPath;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleIdentityVerifier;
import com.routiqo.core.identity.infrastructure.JdbcAuthRateGate;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
    "ROUTIQO_WEB_ORIGIN=http://localhost:3000", "ROUTIQO_AUTH_SECURE_COOKIES=false",
    "ROUTIQO_PLANNING_BACKUP_API_ENABLED=true"
})
@ActiveProfiles({"persistence", "google-auth", "web-auth"})
@Import(BrowserPlanningHttpTest.TestIdentity.class)
class BrowserPlanningHttpTest {
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }
    static final String ORIGIN = "http://localhost:3000";
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("ROUTIQO_AUTH_RATE_SECRET", () -> UUID.randomUUID().toString());
    }
    @TestConfiguration static class TestIdentity {
        @Bean @Primary AuthRateGate fixedRateGate(JdbcTemplate jdbc, @Value("${ROUTIQO_AUTH_RATE_SECRET}") String secret) {
            return new JdbcAuthRateGate(jdbc, secret,
                    Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), java.time.ZoneOffset.UTC));
        }
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
    static HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    HttpResponse<String> send(HttpClient client, String path, String body, String csrf, String origin, String account,
            String contentType) throws Exception {
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
        return send(browser.client(), path, body, browser.csrf(), ORIGIN, browser.account(),
                body == null ? null : "application/json");
    }
    Browser login() throws Exception {
        HttpClient client = client();
        String csrf = JsonPath.read(send(client, "auth/csrf", null, null, null, null, null).body(), "$.token");
        HttpResponse<String> challenge = send(client, "auth/google/challenge", "{}", csrf, ORIGIN, null, "application/json");
        String id = JsonPath.read(challenge.body(), "$.id");
        String nonce = JsonPath.read(challenge.body(), "$.nonce");
        HttpResponse<String> exchange = send(client, "auth/google/exchange",
                "{\"challengeId\":\"" + id + "\",\"idToken\":\"" + nonce + ":" + UUID.randomUUID() + "\"}",
                csrf, ORIGIN, null, "application/json");
        assertThat(exchange.statusCode()).isEqualTo(200);
        return new Browser(client, csrf, JsonPath.read(exchange.body(), "$.accountId"));
    }
    static String plan(String id, String notes) {
        return "{\"id\":\"" + id + "\",\"kind\":\"commute\",\"origin\":\"Navalur\",\"destination\":\"DLF Chennai\","
                + "\"date\":\"2026-09-28\",\"time\":\"08:15\",\"days\":[1,2,3,4,5],\"notes\":\"" + notes + "\","
                + "\"createdAt\":\"2026-09-25T06:00:00.000Z\"}";
    }
    static String write(String plans, String saved, long version, UUID mutation) {
        return "{\"plans\":[" + plans + "],\"saved\":[" + saved + "],\"expectedVersion\":" + version
                + ",\"mutationId\":\"" + mutation + "\"}";
    }
    long rows(String account) {
        return jdbc.queryForObject("SELECT count(*) FROM account_planning_copy WHERE account_id = ?", Long.class,
                UUID.fromString(account));
    }

    @Test void readIsOwnerOnlyAndDerivesVersionZero() throws Exception {
        assertThat(send(client(), "planning", null, null, null, null, null).statusCode()).isEqualTo(401);
        Browser owner = login(); Browser stranger = login();
        HttpResponse<String> absent = send(owner, "planning", null);
        assertThat(absent.statusCode()).isEqualTo(200);
        assertThat(absent.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(JsonPath.<Integer>read(absent.body(), "$.version")).isZero();
        assertThat(JsonPath.<Object>read(absent.body(), "$.updatedAt")).isNull();
        assertThat(JsonPath.<List<Object>>read(absent.body(), "$.plans")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(absent.body(), "$.saved")).isEmpty();

        assertThat(send(owner, "planning", write(plan("mine", "private"), "\"ooty\"", 0, UUID.randomUUID())).statusCode())
                .isEqualTo(200);
        HttpResponse<String> strangerRead = send(stranger, "planning", null);
        assertThat(JsonPath.<Integer>read(strangerRead.body(), "$.version")).isZero();
        assertThat(strangerRead.body()).doesNotContain("mine", "private", "ooty", owner.account());
        assertThat(send(owner.client(), "planning", null, owner.csrf(), ORIGIN, stranger.account(), null).statusCode())
                .isEqualTo(401);
        assertThat(send(owner.client(), "planning", null, owner.csrf(), ORIGIN, null, null).statusCode()).isEqualTo(401);
        HttpResponse<String> ownRead = send(owner, "planning", null);
        assertThat(ownRead.body()).doesNotContain("ownerId", "accountId", owner.account());
    }

    @Test void saveReplaceReplayConflictAndDeleteThroughHttp() throws Exception {
        Browser owner = login();
        UUID first = UUID.randomUUID();
        String firstBody = write(plan("p1", "Line\\nTwo") + "," + plan("p2", ""), "\"kodaikanal\",\"ooty\"", 0, first);
        HttpResponse<String> saved = send(owner, "planning", firstBody);
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(saved.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(JsonPath.<Integer>read(saved.body(), "$.version")).isEqualTo(1);
        assertThat(JsonPath.<String>read(saved.body(), "$.plans[0].notes")).isEqualTo("Line\nTwo");
        assertThat(JsonPath.<List<Integer>>read(saved.body(), "$.plans[0].days")).containsExactly(1, 2, 3, 4, 5);
        assertThat(JsonPath.<String>read(saved.body(), "$.plans[0].kind")).isEqualTo("commute");
        assertThat(JsonPath.<List<String>>read(saved.body(), "$.saved")).containsExactly("kodaikanal", "ooty");
        String updatedAt = JsonPath.read(saved.body(), "$.updatedAt");
        assertThat(Instant.parse(updatedAt)).isNotNull();

        HttpResponse<String> replay = send(owner, "planning", firstBody);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(replay.body(), "$.updatedAt")).isEqualTo(updatedAt);
        HttpResponse<String> reuse = send(owner, "planning", write(plan("changed", ""), "", 0, first));
        assertThat(reuse.statusCode()).isEqualTo(409);
        assertThat(reuse.body()).doesNotContain("changed", "kodaikanal");
        assertThat(send(owner, "planning", write("", "", 0, UUID.randomUUID())).statusCode()).isEqualTo(409);

        HttpResponse<String> replaced = send(owner, "planning", write("", "", 1, UUID.randomUUID()));
        assertThat(JsonPath.<Integer>read(replaced.body(), "$.version")).isEqualTo(2);
        assertThat(JsonPath.<List<Object>>read(send(owner, "planning", null).body(), "$.plans")).isEmpty();

        assertThat(send(owner, "planning/delete", "{\"expectedVersion\":1}").statusCode()).isEqualTo(409);
        assertThat(rows(owner.account())).isEqualTo(1);
        HttpResponse<String> deleted = send(owner, "planning/delete", "{\"expectedVersion\":2}");
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(deleted.body()).isEmpty();
        assertThat(rows(owner.account())).isZero();
        assertThat(send(owner, "planning/delete", "{\"expectedVersion\":2}").statusCode()).isEqualTo(204);
        assertThat(JsonPath.<Integer>read(send(owner, "planning", null).body(), "$.version")).isZero();
    }

    @Test void postsRequireSessionAccountOriginCsrfAndJson() throws Exception {
        Browser owner = login();
        String body = write(plan("p1", ""), "", 0, UUID.randomUUID());
        for (String path : List.of("planning", "planning/delete")) {
            String payload = path.equals("planning") ? body : "{\"expectedVersion\":1}";
            assertThat(send(client(), path, payload, owner.csrf(), ORIGIN, owner.account(), "application/json").statusCode())
                    .as(path).isEqualTo(403);
            assertThat(send(owner.client(), path, payload, owner.csrf(), ORIGIN, null, "application/json").statusCode())
                    .as(path).isEqualTo(401);
            assertThat(send(owner.client(), path, payload, owner.csrf(), ORIGIN, UUID.randomUUID().toString(),
                    "application/json").statusCode()).as(path).isEqualTo(401);
            assertThat(send(owner.client(), path, payload, null, ORIGIN, owner.account(), "application/json").statusCode())
                    .as(path).isEqualTo(403);
            assertThat(send(owner.client(), path, payload, owner.csrf(), "https://attacker.invalid", owner.account(),
                    "application/json").statusCode()).as(path).isEqualTo(403);
            assertThat(send(owner.client(), path, payload, owner.csrf(), ORIGIN, owner.account(), "text/plain").statusCode())
                    .as(path).isEqualTo(415);
        }
        assertThat(rows(owner.account())).isZero();
        // Paths outside the allowlist are denied by the security chain (anonymous to Spring Security, hence 401).
        assertThat(send(owner.client(), "planning/other", null, owner.csrf(), ORIGIN, owner.account(), null).statusCode())
                .isEqualTo(401);
        assertThat(send(owner.client(), "planning/delete", null, owner.csrf(), ORIGIN, owner.account(), null).statusCode())
                .isEqualTo(401);
    }

    @Test void invalidBodiesReturnBadRequestWithoutEchoingContent() throws Exception {
        Browser owner = login(); UUID mutation = UUID.randomUUID();
        for (String body : List.of("{}", "null", "{not-json}",
                write(plan("p1", ""), "\"Bad-Place\"", 0, mutation),
                write(plan("p1", "bad\\u0007bell"), "", 0, mutation),
                write(plan("p1", ""), "", -1, mutation),
                write(plan("p1", "") + "," + plan("p1", ""), "", 0, mutation),
                write(plan("p1", ""), "", 0, mutation).replace("\"days\":[1,2,3,4,5]", "\"days\":[]"),
                write(plan("p1", ""), "", 0, mutation).replace("2026-09-28", "2026-02-30"))) {
            HttpResponse<String> response = send(owner, "planning", body);
            assertThat(response.statusCode()).as(body).isEqualTo(400);
            assertThat(response.body()).doesNotContain("Bad-Place", "bell", "Navalur");
        }
        assertThat(rows(owner.account())).isZero();
    }

    @Test void onlyThePlanningPathAcceptsTheLargerBoundedBody() throws Exception {
        Browser owner = login();
        StringBuilder plans = new StringBuilder();
        for (int index = 0; index < 100; index++) {
            if (index > 0) plans.append(',');
            plans.append(plan("p" + index, "த".repeat(500)));
        }
        String large = write(plans.toString(), "", 0, UUID.randomUUID());
        assertThat(large.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(150_000);
        assertThat(send(owner, "planning", large).statusCode()).isEqualTo(200);
        assertThat(send(owner, "planning", "x".repeat(264 * 1024 + 1)).statusCode()).isEqualTo(413);
        assertThat(send(owner, "planning/delete", "{\"expectedVersion\":1}" + " ".repeat(20 * 1024)).statusCode())
                .isEqualTo(413);
        assertThat(send(owner, "journeys", "{" + " ".repeat(20 * 1024) + "}").statusCode()).isEqualTo(413);
        assertThat(JsonPath.<Integer>read(send(owner, "planning", null).body(), "$.version")).isEqualTo(1);
    }

    @Test void writesShareATwentyPerMinuteAccountBudget() throws Exception {
        Browser owner = login();
        for (int version = 0; version < 19; version++) {
            assertThat(send(owner, "planning", write("", "", version, UUID.randomUUID())).statusCode())
                    .as("write %s", version + 1).isEqualTo(200);
        }
        assertThat(send(owner, "planning/delete", "{\"expectedVersion\":19}").statusCode()).isEqualTo(204);
        HttpResponse<String> limited = send(owner, "planning", write("", "", 0, UUID.randomUUID()));
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).contains("60");
        assertThat(send(owner, "planning/delete", "{\"expectedVersion\":1}").statusCode()).isEqualTo(429);
        assertThat(send(owner, "planning", null).statusCode()).isEqualTo(200);
        assertThat(rows(owner.account())).isZero();
    }

    @Test void accountDeletionRemovesTheCopy() throws Exception {
        Browser owner = login();
        assertThat(send(owner, "planning", write(plan("p1", ""), "", 0, UUID.randomUUID())).statusCode()).isEqualTo(200);
        HttpResponse<String> deletion = send(owner, "auth/account/delete",
                "{\"confirmation\":\"DELETE\",\"accountId\":\"" + owner.account() + "\"}");
        assertThat(deletion.statusCode()).isBetween(200, 204);
        assertThat(rows(owner.account())).isZero();
        assertThat(send(owner, "planning", null).statusCode()).isEqualTo(401);
    }
}
