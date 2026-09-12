package com.routiqo.core.identity.api;

import com.jayway.jsonpath.JsonPath;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleIdentityVerifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com",
    "ROUTIQO_WEB_ORIGIN=http://localhost:3000", "ROUTIQO_AUTH_SECURE_COOKIES=false"
})
@ActiveProfiles({"persistence", "google-auth", "web-auth", "native-auth"})
@Import(NativeAuthHttpTest.TestIdentity.class)
class NativeAuthHttpTest {
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static final AtomicInteger VERIFICATIONS = new AtomicInteger();
    static { DATABASE.start(); }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        databaseProperties(properties);
    }

    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("ROUTIQO_AUTH_RATE_SECRET", () -> "native-auth-test-rate-secret-32-chars");
    }

    @TestConfiguration static class TestIdentity {
        @Bean @Primary Clock fixedRateClock() {
            return Clock.fixed(java.time.Instant.parse("2026-09-12T12:00:00Z"), java.time.ZoneOffset.UTC);
        }

        @Bean @Primary GoogleIdentityVerifier localTestVerifier() {
            return (token, nonce) -> {
                VERIFICATIONS.incrementAndGet();
                if (!nonce.equals(token)) throw new SecurityException("Test verification rejected");
                return new GoogleIdentityVerifier.Identity("google", "native-http-test-subject");
            };
        }
    }

    record Login(String accountId, String credential) {}

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthRateGate rates;
    HttpClient client;

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM auth_rate_bucket");
        jdbc.update("DELETE FROM login_challenge");
        jdbc.update("DELETE FROM routiqo_account");
        VERIFICATIONS.set(0);
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpResponse<String> request(String method, String path, String body, String authorization,
            List<String[]> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/native/auth/" + path));
        if (authorization != null) builder.header("Authorization", authorization);
        for (var header : headers) builder.header(header[0], header[1]);
        if (method.equals("POST")) {
            boolean hasContentType = headers.stream().anyMatch(h -> h[0].equalsIgnoreCase("Content-Type"));
            if (!hasContentType) builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
        } else if (method.equals("GET")) builder.GET();
        else builder.method(method, HttpRequest.BodyPublishers.noBody());
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(
                value -> assertThat(value).contains("no-store"));
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.headers().allValues("Access-Control-Allow-Origin")).isEmpty();
        assertThat(response.headers().allValues("Location")).isEmpty();
        return response;
    }

    HttpResponse<String> post(String path, String body, String credential) throws Exception {
        return request("POST", path, body, credential == null ? null : "Bearer " + credential, List.of());
    }

    HttpResponse<String> get(String path, String credential) throws Exception {
        return request("GET", path, "", credential == null ? null : "Bearer " + credential, List.of());
    }

    Login login() throws Exception {
        var challenge = post("google/challenge", "{}", null);
        assertThat(challenge.statusCode()).isEqualTo(200);
        String challengeId = JsonPath.read(challenge.body(), "$.id");
        String nonce = JsonPath.read(challenge.body(), "$.nonce");
        String binding = JsonPath.read(challenge.body(), "$.binding");
        var exchange = post("google/exchange", exchangeBody(challengeId, binding, nonce), null);
        assertThat(exchange.statusCode()).isEqualTo(200);
        return new Login(JsonPath.read(exchange.body(), "$.accountId"), JsonPath.read(exchange.body(), "$.credential"));
    }

    static String exchangeBody(String challengeId, String binding, String token) {
        return "{\"challengeId\":\"" + challengeId + "\",\"binding\":\"" + binding
                + "\",\"idToken\":\"" + token + "\"}";
    }

    @Test void challengeExchangeAndReplayUseBoundNativeCredentials() throws Exception {
        var challenge = post("google/challenge", "{}", null);
        assertThat(challenge.statusCode()).isEqualTo(200);
        assertThat(challenge.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(challenge.headers().allValues("Set-Cookie")).isEmpty();
        String id = JsonPath.read(challenge.body(), "$.id");
        String nonce = JsonPath.read(challenge.body(), "$.nonce");
        String binding = JsonPath.read(challenge.body(), "$.binding");
        assertThat(nonce).hasSize(43);
        assertThat(binding).hasSize(43);

        assertThat(post("google/exchange", exchangeBody(id, "x".repeat(43), nonce), null).statusCode()).isEqualTo(401);
        var exchange = post("google/exchange", exchangeBody(id, binding, nonce), null);
        assertThat(exchange.statusCode()).isEqualTo(200);
        String accountId = JsonPath.read(exchange.body(), "$.accountId");
        String credential = JsonPath.read(exchange.body(), "$.credential");
        assertThat(credential).hasSize(43);
        assertThat(exchange.body()).doesNotContain("idToken", nonce, binding);
        assertThat(get("session", credential).body()).contains(accountId).doesNotContain(credential);
        assertThat(post("google/exchange", exchangeBody(id, binding, nonce), null).statusCode()).isEqualTo(401);
    }

    @Test void bearerAndBrowserTransportSourcesAreStrictlyRejected() throws Exception {
        Login login = login();
        assertThat(get("session", null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "session", "", "bearer " + login.credential(), List.of()).statusCode()).isEqualTo(401);
        assertThat(request("GET", "session", "", "Bearer  " + login.credential(), List.of()).statusCode()).isEqualTo(401);
        assertThat(request("GET", "session", "", "Bearer " + login.credential() + ",Bearer " + login.credential(), List.of()).statusCode()).isEqualTo(401);
        var duplicated = new ArrayList<String[]>();
        duplicated.add(new String[] {"Authorization", "Bearer " + login.credential()});
        duplicated.add(new String[] {"Authorization", "Bearer " + login.credential()});
        assertThat(request("GET", "session", "", null, duplicated).statusCode()).isEqualTo(401);
        assertThat(request("POST", "google/challenge", "{}", "Bearer " + login.credential(), List.of()).statusCode()).isEqualTo(401);

        for (String header : List.of("Cookie", "Origin", "Sec-Fetch-Site")) {
            assertThat(request("GET", "session", "", "Bearer " + login.credential(),
                    List.<String[]>of(new String[] {header, "blocked"})).statusCode()).isEqualTo(403);
            assertThat(request("GET", "session", "", "Bearer " + login.credential(),
                    List.<String[]>of(new String[] {header, ""})).statusCode()).isEqualTo(403);
        }
        // HttpClient removes an empty query delimiter; send the exact request target.
        try (var socket = new java.net.Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /api/v1/native/auth/session? HTTP/1.1\r\nHost: localhost\r\n"
                    + "Authorization: Bearer " + login.credential() + "\r\nConnection: close\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            var response = new String(socket.getInputStream().readNBytes(8192), java.nio.charset.StandardCharsets.US_ASCII);
            assertThat(response.substring(0, response.indexOf("\r\n"))).isEqualTo("HTTP/1.1 403 ");
        }
        assertThat(get("session?credential=" + login.credential(), login.credential()).statusCode()).isEqualTo(403);
    }

    @Test void disabledAndExpiredCredentialsCannotReadRenewOrDelete() throws Exception {
        Login login = login();
        UUID accountId = UUID.fromString(login.accountId());
        String deletion = "{\"confirmation\":\"DELETE\",\"accountId\":\"" + accountId + "\"}";
        jdbc.update("UPDATE routiqo_account SET enabled = FALSE WHERE id = ?", accountId);
        assertThat(get("session", login.credential()).statusCode()).isEqualTo(401);
        assertThat(post("session/renew", "{}", login.credential()).statusCode()).isEqualTo(401);
        assertThat(post("account/delete", deletion, login.credential()).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE routiqo_account SET enabled = TRUE WHERE id = ?", accountId);
        jdbc.update("UPDATE auth_session SET authenticated_at = CURRENT_TIMESTAMP - INTERVAL '20 minutes', "
                + "created_at = CURRENT_TIMESTAMP - INTERVAL '20 minutes', "
                + "expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE account_id = ?", accountId);
        assertThat(get("session", login.credential()).statusCode()).isEqualTo(401);
        assertThat(post("session/renew", "{}", login.credential()).statusCode()).isEqualTo(401);
        assertThat(post("account/delete", deletion, login.credential()).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM routiqo_account WHERE id = ?", Integer.class, accountId)).isEqualTo(1);
    }

    @Test void nativeBearerDoesNotAuthorizeBrowserJourneyResources() throws Exception {
        Login login = login();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/journeys"))
                .header("Authorization", "Bearer " + login.credential()).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain(login.credential(), login.accountId());
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test void chunkedBodyCannotBypassRequestSizeLimit() throws Exception {
        byte[] oversized = "x".repeat(20 * 1024 + 1).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var body = HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(oversized));
        assertThat(body.contentLength()).isEqualTo(-1);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/native/auth/google/challenge"))
                .version(HttpClient.Version.HTTP_1_1).header("Content-Type", "application/json").POST(body).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_challenge", Integer.class)).isZero();
        assertThat(VERIFICATIONS).hasValue(0);
    }

    @Test void postBodiesRequireBoundedStrictJsonWithExactFields() throws Exception {
        assertThat(post("google/challenge", "[]", null).statusCode()).isEqualTo(400);
        assertThat(post("google/challenge", "{\"unexpected\":true}", null).statusCode()).isEqualTo(400);
        assertThat(post("google/challenge", "{}{}", null).statusCode()).isEqualTo(400);
        assertThat(request("POST", "google/challenge", "{}", null,
                List.<String[]>of(new String[] {"Content-Type", "text/plain"})).statusCode()).isEqualTo(415);
        assertThat(post("google/challenge", "x".repeat(20 * 1024 + 1), null).statusCode()).isEqualTo(413);

        var challenge = post("google/challenge", "{}", null);
        String id = JsonPath.read(challenge.body(), "$.id");
        String nonce = JsonPath.read(challenge.body(), "$.nonce");
        String binding = JsonPath.read(challenge.body(), "$.binding");
        assertThat(post("google/exchange", "{\"challengeId\":\"" + id + "\",\"challengeId\":\"" + id
                + "\",\"binding\":\"" + binding + "\",\"idToken\":\"" + nonce + "\"}", null).statusCode()).isEqualTo(400);
        assertThat(post("google/exchange", exchangeBody(id.toUpperCase(), binding, nonce), null).statusCode()).isEqualTo(400);
        assertThat(post("google/exchange", exchangeBody(id, binding, "x".repeat(16_385)), null).statusCode()).isEqualTo(400);
        assertThat(post("google/exchange", "{\"challengeId\":\"" + id + "\",\"binding\":7,\"idToken\":\"" + nonce + "\"}", null).statusCode()).isEqualTo(400);
    }

    @Test void renewalLogoutAndDeletionEnforceSessionLifecycle() throws Exception {
        Login login = login();
        jdbc.update("UPDATE auth_session SET expires_at = CURRENT_TIMESTAMP + INTERVAL '4 minutes' WHERE account_id = ?",
                UUID.fromString(login.accountId()));
        var renewed = post("session/renew", "{}", login.credential());
        assertThat(renewed.statusCode()).isEqualTo(200);
        String replacement = JsonPath.read(renewed.body(), "$.credential");
        assertThat(replacement).isNotEqualTo(login.credential()).hasSize(43);
        assertThat(get("session", login.credential()).statusCode()).isEqualTo(401);
        assertThat(get("session", replacement).statusCode()).isEqualTo(200);
        Login unrelatedLineage = login();
        assertThat(post("logout", "{\"unexpected\":true}", login.credential()).statusCode()).isEqualTo(400);
        assertThat(post("logout", "{}", login.credential()).statusCode()).isEqualTo(204);
        assertThat(get("session", replacement).statusCode()).isEqualTo(401);
        assertThat(get("session", unrelatedLineage.credential()).statusCode()).isEqualTo(200);
        assertThat(post("logout", "{}", login.credential()).statusCode()).isEqualTo(204);
        assertThat(post("logout", "{}", "z".repeat(43)).statusCode()).isEqualTo(204);

        Login deletion = login();
        String wrongAccount = "{\"confirmation\":\"DELETE\",\"accountId\":\"" + UUID.randomUUID() + "\"}";
        assertThat(post("account/delete", wrongAccount, deletion.credential()).statusCode()).isEqualTo(401);
        String deleteBody = "{\"confirmation\":\"DELETE\",\"accountId\":\"" + deletion.accountId() + "\"}";
        jdbc.update("UPDATE auth_session SET authenticated_at = created_at - INTERVAL '6 minutes' WHERE account_id = ?",
                UUID.fromString(deletion.accountId()));
        assertThat(post("account/delete", deleteBody, deletion.credential()).statusCode()).isEqualTo(428);
        Login reauthenticated = login();
        assertThat(reauthenticated.accountId()).isEqualTo(deletion.accountId());
        assertThat(post("account/delete", deleteBody, reauthenticated.credential()).statusCode()).isEqualTo(204);
        assertThat(get("session", reauthenticated.credential()).statusCode()).isEqualTo(401);
    }

    @Test void databaseRatesSeparateTransportsAndBoundChallengeAndAccountAbuse() throws Exception {
        for (int i = 0; i < 10; i++) assertThat(post("google/challenge", "{}", null).statusCode()).isEqualTo(200);
        var limited = post("google/challenge", "{}", null);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).contains("60");

        jdbc.update("DELETE FROM auth_rate_bucket");
        var challenge = post("google/challenge", "{}", null);
        String id = JsonPath.read(challenge.body(), "$.id");
        String binding = JsonPath.read(challenge.body(), "$.binding");
        for (int i = 0; i < 20; i++)
            assertThat(rates.allow(id, "native-exchange-challenge", 20)).isTrue();
        var challengeLimited = post("google/exchange", exchangeBody(id, binding, "wrong-final"), null);
        assertThat(challengeLimited.statusCode()).isEqualTo(429);
        assertThat(VERIFICATIONS).hasValue(0);

        jdbc.update("DELETE FROM auth_rate_bucket");
        Login login = login();
        jdbc.update("UPDATE auth_session SET expires_at = CURRENT_TIMESTAMP + INTERVAL '4 minutes' WHERE account_id = ?",
                UUID.fromString(login.accountId()));
        for (int i = 0; i < 118; i++) assertThat(rates.allow(login.accountId(), "native-account", 120)).isTrue();
        var renewed = post("session/renew", "{}", login.credential());
        assertThat(renewed.statusCode()).isEqualTo(200);
        String replacement = JsonPath.read(renewed.body(), "$.credential");
        assertThat(post("logout", "{}", login.credential()).statusCode()).isEqualTo(204);
        assertThat(get("session", replacement).statusCode()).isEqualTo(401);
        assertThat(post("logout", "{}", login.credential()).statusCode()).isEqualTo(429);
    }

    @Test void unknownNativeRoutesAndMethodsAreDeniedAndWebCsrfStillApplies() throws Exception {
        assertThat(request("GET", "google/challenge", "", null, List.of()).statusCode()).isEqualTo(403);
        assertThat(request("DELETE", "session", "", null, List.of()).statusCode()).isEqualTo(403);
        assertThat(get("unknown", null).statusCode()).isEqualTo(403);

        var browser = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/google/challenge"))
                .header("Content-Type", "application/json").header("Origin", "http://localhost:3000")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertThat(client.send(browser, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }
}
