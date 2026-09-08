package com.routiqo.core.journey.api;

import com.routiqo.core.identity.application.GoogleIdentityVerifier;
import com.jayway.jsonpath.JsonPath;
import java.net.*;
import java.net.http.*;
import java.util.UUID;
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
@Import(BrowserJourneyHttpTest.TestIdentity.class)
class BrowserJourneyHttpTest {
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
                return new GoogleIdentityVerifier.Identity("google", UUID.fromString(token.substring(nonce.length() + 1)).toString());
            };
        }
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void rateBuckets() { jdbc.update("DELETE FROM auth_rate_bucket"); }
    record Browser(HttpClient client, String csrf, String account) {}
    HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); }
    HttpResponse<String> send(HttpClient client, String path, String body, String csrf, String origin) throws Exception {
        return send(client, path, body, csrf, origin, null);
    }
    HttpResponse<String> send(HttpClient client, String path, String body, String csrf, String origin, String account) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/" + path))
                .timeout(java.time.Duration.ofSeconds(10));
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (csrf != null) builder.header("X-XSRF-TOKEN", csrf);
        if (origin != null) builder.header("Origin", origin);
        if (account != null) builder.header("X-Routiqo-Account", account);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> send(Browser browser, String path, String body) throws Exception {
        return send(browser.client(), path, body, browser.csrf(), "http://localhost:3000", browser.account());
    }
    Browser login() throws Exception {
        var client = client();
        String csrf = JsonPath.read(send(client, "auth/csrf", null, null, null).body(), "$.token");
        var challenge = send(client, "auth/google/challenge", "{}", csrf, "http://localhost:3000");
        String id = JsonPath.read(challenge.body(), "$.id"), nonce = JsonPath.read(challenge.body(), "$.nonce");
        var exchange = send(client, "auth/google/exchange", "{\"challengeId\":\"" + id + "\",\"idToken\":\"" + nonce + ":" + UUID.randomUUID() + "\"}", csrf, "http://localhost:3000");
        assertThat(exchange.statusCode()).isEqualTo(200);
        return new Browser(client, csrf, JsonPath.read(exchange.body(), "$.accountId"));
    }
    String start(UUID id, String kind) { return "{\"id\":\"" + id + "\",\"kind\":\"" + kind + "\"}"; }
    @Test void lifecycleRetriesNeverRestartOrChangeOwnership() throws Exception {
        var owner = login(); var other = login(); var id = UUID.randomUUID();
        String forged = start(id, "trip").replace("}", ",\"ownerId\":\"" + other.account() + "\"}");
        var started = send(owner, "journeys", forged);
        assertThat(started.statusCode()).isEqualTo(200);
        assertThat(started.body()).doesNotContain("ownerId", owner.account(), other.account());
        assertThat(started.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(send(owner, "journeys", start(id, "trip")).body()).isEqualTo(started.body());
        assertThat(send(other, "journeys/" + id, null).statusCode()).isEqualTo(404);
        assertThat(send(other, "journeys/" + id + "/complete", "{}").statusCode()).isEqualTo(404);
        assertThat(send(other, "journeys/" + UUID.randomUUID(), null).statusCode()).isEqualTo(404);
        assertThat(send(other, "journeys", start(id, "trip")).statusCode()).isEqualTo(409);
        assertThat(send(owner, "journeys", start(id, "commute")).statusCode()).isEqualTo(409);
        assertThat(send(owner, "journeys", start(UUID.randomUUID(), "trip")).statusCode()).isEqualTo(409);
        var completed = send(owner, "journeys/" + id + "/complete", "{}");
        assertThat(completed.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(completed.body(), "$.status")).isEqualTo("completed");
        assertThat(send(owner, "journeys/" + id + "/complete", "{}").body()).isEqualTo(completed.body());
        assertThat(send(owner, "journeys", start(id, "trip")).body()).isEqualTo(completed.body());
    }
    @Test void authenticationOriginAndCsrfAreRequiredForJourneyWrites() throws Exception {
        assertThat(send(client(), "journeys", null, null, null).statusCode()).isEqualTo(401);
        var owner = login(); String body = start(UUID.randomUUID(), "trip");
        assertThat(send(owner.client(), "journeys", body, owner.csrf(), "http://localhost:3000", UUID.randomUUID().toString()).statusCode()).isEqualTo(401);
        assertThat(send(owner.client(), "journeys", body, owner.csrf(), "http://localhost:3000").statusCode()).isEqualTo(401);
        assertThat(send(owner.client(), "journeys", body, null, "http://localhost:3000").statusCode()).isEqualTo(403);
        assertThat(send(owner.client(), "journeys", body, owner.csrf(), "https://attacker.invalid").statusCode()).isEqualTo(403);
        assertThat(send(owner, "auth/logout", "{}").statusCode()).isEqualTo(204);
        assertThat(send(owner, "journeys", body).statusCode()).isEqualTo(401);
        assertThat(send(owner, "journeys", null).statusCode()).isEqualTo(401);
    }
    @Test void paginationIsBoundedAndOwnerScoped() throws Exception {
        var owner = login(); var other = login();
        for (int i = 0; i < 3; i++) {
            var id = UUID.randomUUID();
            assertThat(send(owner, "journeys", start(id, "trip")).statusCode()).isEqualTo(200);
            assertThat(send(owner, "journeys/" + id + "/complete", "{}").statusCode()).isEqualTo(200);
        }
        var page = send(owner, "journeys?limit=2", null);
        assertThat(JsonPath.<java.util.List<?>>read(page.body(), "$.journeys")).hasSize(2);
        String before = URLEncoder.encode(JsonPath.read(page.body(), "$.next.startedAt"), java.nio.charset.StandardCharsets.UTF_8);
        String id = JsonPath.read(page.body(), "$.next.id");
        String path = "journeys?limit=2&beforeStartedAt=" + before + "&beforeId=" + id;
        var last = send(owner, path, null);
        assertThat(JsonPath.<java.util.List<?>>read(last.body(), "$.journeys")).hasSize(1);
        assertThat(JsonPath.<Object>read(last.body(), "$.next")).isNull();
        assertThat(JsonPath.<java.util.List<?>>read(send(other, path, null).body(), "$.journeys")).isEmpty();
    }
    @Test void malformedIdsKindsAndCursorsFailWithoutWrites() throws Exception {
        var owner = login();
        for (String path : java.util.List.of("journeys?limit=0", "journeys?limit=51", "journeys?limit=abc",
                "journeys?beforeId=" + UUID.randomUUID(), "journeys?beforeStartedAt=bad&beforeId=" + UUID.randomUUID(),
                "journeys/1-1-1-1-1")) assertThat(send(owner, path, null).statusCode()).isEqualTo(400);
        assertThat(send(owner, "journeys", "{}").statusCode()).isEqualTo(400);
        assertThat(send(owner, "journeys", start(UUID.randomUUID(), "unknown")).statusCode()).isEqualTo(400);
        assertThat(send(owner, "journeys", "x".repeat(20481)).statusCode()).isEqualTo(413);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journey WHERE owner_id = ?", Long.class, UUID.fromString(owner.account()))).isZero();
    }
}
