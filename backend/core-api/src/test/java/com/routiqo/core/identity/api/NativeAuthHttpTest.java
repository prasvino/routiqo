package com.routiqo.core.identity.api;

import com.jayway.jsonpath.JsonPath;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleIdentityVerifier;
import com.routiqo.core.journal.application.JournalService;
import com.routiqo.core.journal.domain.JournalMutation;
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
                if (nonce.equals(token))
                    return new GoogleIdentityVerifier.Identity("google", "native-http-test-subject");
                if ((nonce + "-other").equals(token))
                    return new GoogleIdentityVerifier.Identity("google", "native-http-other-subject");
                throw new SecurityException("Test verification rejected");
            };
        }
    }

    record Login(String accountId, String credential) {}

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthRateGate rates;
    @Autowired JournalService journals;
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

    Login loginOther() throws Exception {
        var challenge = post("google/challenge", "{}", null);
        String challengeId = JsonPath.read(challenge.body(), "$.id");
        String nonce = JsonPath.read(challenge.body(), "$.nonce");
        String binding = JsonPath.read(challenge.body(), "$.binding");
        var exchange = post("google/exchange", exchangeBody(challengeId, binding, nonce + "-other"), null);
        assertThat(exchange.statusCode()).isEqualTo(200);
        return new Login(JsonPath.read(exchange.body(), "$.accountId"), JsonPath.read(exchange.body(), "$.credential"));
    }

    HttpResponse<String> journey(String method, String path, String body, Login identity,
            List<String[]> extra) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/native/journeys" + path));
        if (identity != null) {
            builder.header("Authorization", "Bearer " + identity.credential());
            builder.header("X-Routiqo-Account", identity.accountId());
        }
        for (var header : extra) builder.header(header[0], header[1]);
        if (method.equals("POST")) {
            if (extra.stream().noneMatch(header -> header[0].equalsIgnoreCase("Content-Type")))
                builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
        }
        else if (method.equals("GET")) builder.GET();
        else builder.method(method, HttpRequest.BodyPublishers.noBody());
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(
                value -> assertThat(value).contains("no-store"));
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        return response;
    }

    @Test void nativeJourneysRemainOwnerScopedAndIdempotent() throws Exception {
        Login owner = login(), stranger = loginOther();
        String id = UUID.randomUUID().toString();
        String start = "{\"id\":\"" + id + "\",\"kind\":\"trip\"}";
        var first = journey("POST", "", start, owner, List.of());
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(journey("POST", "", start, owner, List.of()).body()).isEqualTo(first.body());
        assertThat(journey("GET", "/" + id, "", owner, List.of()).statusCode()).isEqualTo(200);
        assertThat(journey("GET", "/" + id, "", stranger, List.of()).statusCode()).isEqualTo(404);
        assertThat(journey("POST", "/" + id + "/complete", "{}", stranger, List.of()).statusCode()).isEqualTo(404);
        assertThat(journey("GET", "/" + id, "", null, List.of()).statusCode()).isEqualTo(401);
        var completed = journey("POST", "/" + id + "/complete", "{}", owner, List.of());
        assertThat(completed.statusCode()).isEqualTo(200);
        assertThat(journey("POST", "/" + id + "/complete", "{}", owner, List.of()).body())
                .isEqualTo(completed.body());
        assertThat(journey("GET", "", "", owner, List.of()).body()).contains(id);
        assertThat(journey("GET", "", "", stranger, List.of()).body()).doesNotContain(id);
    }

    @Test void nativeJourneyTransportRejectsBrowserSourcesAndMalformedCommands() throws Exception {
        Login owner = login();
        String id = UUID.randomUUID().toString();
        assertThat(journey("POST", "", "{\"id\":\"" + id + "\",\"kind\":\"trip\",\"extra\":1}",
                owner, List.of()).statusCode()).isEqualTo(400);
        assertThat(journey("POST", "", "{\"id\":\"" + id + "\",\"kind\":\"trip\",\"kind\":\"trip\"}",
                owner, List.of()).statusCode()).isEqualTo(400);
        assertThat(journey("GET", "?limit=1", "", owner, List.of()).statusCode()).isEqualTo(403);
        assertThat(journey("GET", "", "", owner, List.<String[]>of(
                new String[] {"Cookie", "routiqo_session=forbidden"})).statusCode()).isEqualTo(403);
        assertThat(journey("GET", "", "", owner, List.<String[]>of(
                new String[] {"Origin", "https://example.org"})).statusCode()).isEqualTo(403);
        assertThat(journey("DELETE", "", "", owner, List.of()).statusCode()).isEqualTo(403);
    }

    @Test void nativeJournalReadIsOwnerOnlyCompletedTripAndDefaultDeny() throws Exception {
        Login owner = login(), other = loginOther();
        String trip = UUID.randomUUID().toString();
        String activeTrip = UUID.randomUUID().toString();
        String commute = UUID.randomUUID().toString();
        assertThat(journey("POST", "", "{\"id\":\"" + trip + "\",\"kind\":\"trip\"}", owner, List.of()).statusCode()).isEqualTo(200);
        assertThat(journey("POST", "/" + trip + "/complete", "{}", owner, List.of()).statusCode()).isEqualTo(200);
        assertThat(journey("POST", "", "{\"id\":\"" + activeTrip + "\",\"kind\":\"trip\"}", owner, List.of()).statusCode()).isEqualTo(200);
        var journal = journey("GET", "/" + trip + "/journal", "", owner, List.of());
        assertThat(journal.statusCode()).isEqualTo(200);
        assertThat(journal.body()).contains(trip, "\"title\":\"\"", "\"notes\":\"\"", "\"version\":0", "\"updatedAt\":null");
        assertThat(journal.body()).doesNotContain(owner.accountId(), other.accountId());
        var saved = journals.save(UUID.fromString(owner.accountId()), UUID.fromString(trip),
                new JournalMutation("Café trip", "First stop\nSecond stop", 0, UUID.randomUUID()));
        assertThat(saved.annotation().version()).isEqualTo(1);
        var populated = journey("GET", "/" + trip + "/journal", "", owner, List.of());
        assertThat(populated.statusCode()).isEqualTo(200);
        assertThat(populated.body()).contains("Café trip", "First stop\\nSecond stop", "\"version\":1", "\"updatedAt\":");
        assertThat(journey("GET", "/" + trip + "/journal", "", other, List.of()).statusCode()).isEqualTo(404);
        assertThat(journey("GET", "/" + trip + "/journal", "", null, List.of()).statusCode()).isEqualTo(401);
        assertThat(journey("GET", "/" + activeTrip + "/journal", "", owner, List.of()).statusCode()).isEqualTo(409);
        assertThat(journey("POST", "/" + activeTrip + "/complete", "{}", owner, List.of()).statusCode()).isEqualTo(200);
        assertThat(journey("POST", "", "{\"id\":\"" + commute + "\",\"kind\":\"commute\"}", owner, List.of()).statusCode()).isEqualTo(200);
        assertThat(journey("POST", "/" + commute + "/complete", "{}", owner, List.of()).statusCode()).isEqualTo(200);
        assertThat(journey("GET", "/" + commute + "/journal", "", owner, List.of()).statusCode()).isEqualTo(409);
        assertThat(journey("GET", "/" + UUID.randomUUID() + "/journal", "", owner, List.of()).statusCode()).isEqualTo(404);
        assertThat(journey("GET", "/" + trip.toUpperCase() + "/journal", "", owner, List.of()).statusCode()).isEqualTo(400);
        assertThat(journey("GET", "/" + trip + "/journal?x=1", "", owner, List.of()).statusCode()).isEqualTo(403);
        assertThat(journey("GET", "/" + trip + "/journal", "", owner,
                List.<String[]>of(new String[] {"Cookie", "routiqo_session=forbidden"})).statusCode()).isEqualTo(403);
        assertThat(journey("GET", "/" + trip + "/journal", "", owner,
                List.<String[]>of(new String[] {"Origin", "https://example.org"})).statusCode()).isEqualTo(403);
        assertThat(journey("GET", "/" + trip + "/journal", "", owner,
                List.<String[]>of(new String[] {"X-Routiqo-Account", other.accountId()})).statusCode()).isEqualTo(401);
        assertThat(journey("POST", "/" + trip + "/journal", "{}", owner, List.of()).statusCode()).isEqualTo(400);
        assertThat(journey("DELETE", "/" + trip + "/journal", "", owner, List.of()).statusCode()).isEqualTo(403);
        assertThat(journey("GET", "/" + trip + "/journal/extra", "", owner, List.of()).statusCode()).isEqualTo(403);
        assertThat(journey("GET", "/" + trip + "/journal", "", owner, List.of()).body()).isEqualTo(populated.body());
    }

    static String journalWrite(String title, String notes, String version, String mutation) {
        return "{\"title\":\"" + title + "\",\"notes\":\"" + notes
                + "\",\"expectedVersion\":" + version + ",\"mutationId\":\"" + mutation + "\"}";
    }

    @Test void nativeJournalWriteUsesOwnerCasReplayAndStrictTransport() throws Exception {
        Login owner = login(), other = loginOther();
        String trip = UUID.randomUUID().toString(), active = UUID.randomUUID().toString();
        assertThat(journey("POST", "", "{\"id\":\"" + trip + "\",\"kind\":\"trip\"}", owner, List.of()).statusCode()).isEqualTo(200);
        assertThat(journey("POST", "/" + trip + "/complete", "{}", owner, List.of()).statusCode()).isEqualTo(200);
        assertThat(journey("POST", "", "{\"id\":\"" + active + "\",\"kind\":\"trip\"}", owner, List.of()).statusCode()).isEqualTo(200);
        String mutation = UUID.randomUUID().toString();
        String first = journalWrite("Café", "first\\nsecond", "0", mutation);
        var saved = journey("POST", "/" + trip + "/journal", first, owner, List.of());
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(saved.body()).contains("Café", "first\\nsecond", "\"version\":1");
        assertThat(journey("POST", "/" + trip + "/journal", first, owner, List.of()).body()).isEqualTo(saved.body());
        assertThat(journey("POST", "/" + trip + "/journal", journalWrite("changed", "first\\nsecond", "0", mutation), owner, List.of()).statusCode()).isEqualTo(409);
        assertThat(journey("POST", "/" + trip + "/journal", journalWrite("stale", "", "0", UUID.randomUUID().toString()), owner, List.of()).statusCode()).isEqualTo(409);
        assertThat(journey("POST", "/" + trip + "/journal", journalWrite("foreign", "", "1", UUID.randomUUID().toString()), other, List.of()).statusCode()).isEqualTo(404);
        assertThat(journey("POST", "/" + trip + "/journal", first, null, List.of()).statusCode()).isEqualTo(401);
        assertThat(journey("POST", "/" + active + "/journal", first, owner, List.of()).statusCode()).isEqualTo(409);
        assertThat(journey("POST", "/" + trip + "/journal?x=1", first, owner, List.of()).statusCode()).isEqualTo(403);
        assertThat(journey("POST", "/" + trip + "/journal", first, owner,
                List.<String[]>of(new String[] {"Cookie", "routiqo_session=forbidden"})).statusCode()).isEqualTo(403);
        assertThat(journey("POST", "/" + trip + "/journal", first, owner,
                List.<String[]>of(new String[] {"Origin", "https://example.org"})).statusCode()).isEqualTo(403);
        assertThat(journey("POST", "/" + trip + "/journal", first, owner,
                List.<String[]>of(new String[] {"X-Routiqo-Account", other.accountId()})).statusCode()).isEqualTo(401);
        assertThat(journey("POST", "/" + trip + "/journal", first, owner,
                List.<String[]>of(new String[] {"Content-Type", "text/plain"})).statusCode()).isEqualTo(415);
        assertThat(journey("GET", "/" + trip + "/journal", "", owner, List.of()).body()).isEqualTo(saved.body());
    }

    @Test void nativeJournalWriteRejectsUnknownDuplicateAndInexactNumbers() throws Exception {
        Login owner = login();
        String trip = UUID.randomUUID().toString(), mutation = UUID.randomUUID().toString();
        assertThat(journey("POST", "", "{\"id\":\"" + trip + "\",\"kind\":\"trip\"}", owner, List.of()).statusCode()).isEqualTo(200);
        assertThat(journey("POST", "/" + trip + "/complete", "{}", owner, List.of()).statusCode()).isEqualTo(200);
        String valid = journalWrite("title", "notes", "0", mutation);
        for (String body : List.of(
                valid.replace("\"expectedVersion\":0", "\"expectedVersion\":0.5"),
                valid.replace("\"expectedVersion\":0", "\"expectedVersion\":9007199254740991"),
                valid.replace("\"expectedVersion\":0", "\"expectedVersion\":1e101"),
                valid.replace("\"expectedVersion\":0", "\"expectedVersion\":\"0\""),
                valid.replace("\"expectedVersion\":0", "\"expectedVersion\":false"),
                valid.replace("\"title\":\"title\"", "\"title\":\"bad\\u0000\""),
                valid.replace("\"title\":\"title\"", "\"title\":\"bad\\ud800\""),
                valid.replace("\"mutationId\":\"" + mutation + "\"", "\"mutationId\":\"" + mutation.toUpperCase() + "\""),
                valid.replace("\"notes\":\"notes\"", "\"notes\":4"),
                valid.replace("\"notes\":\"notes\"", "\"notes\":\"notes\",\"extra\":1"),
                valid.replace("\"notes\":\"notes\"", "\"notes\":\"notes\",\"notes\":\"again\""),
                "{}", "[]", valid + "{}")) {
            assertThat(journey("POST", "/" + trip + "/journal", body, owner, List.of()).statusCode()).isEqualTo(400);
        }
        assertThat(journey("GET", "/" + trip + "/journal", "", owner, List.of()).body()).contains("\"version\":0");
        String integral = journalWrite("title", "notes", "0e0", mutation);
        assertThat(journey("POST", "/" + trip + "/journal", integral, owner, List.of()).statusCode()).isEqualTo(200);
    }

    @Test void nativeJournalWriteSharesBrowserAccountQuota() throws Exception {
        Login owner = login();
        String trip = UUID.randomUUID().toString();
        assertThat(journey("POST", "", "{\"id\":\"" + trip + "\",\"kind\":\"trip\"}", owner, List.of()).statusCode()).isEqualTo(200);
        assertThat(journey("POST", "/" + trip + "/complete", "{}", owner, List.of()).statusCode()).isEqualTo(200);
        for (int count = 0; count < 19; count++)
            assertThat(rates.allow(owner.accountId(), "journal-write-account", 20)).isTrue();
        String body = journalWrite("saved", "", "0", UUID.randomUUID().toString());
        assertThat(journey("POST", "/" + trip + "/journal", body, owner, List.of()).statusCode()).isEqualTo(200);
        var denied = journey("POST", "/" + trip + "/journal", body, owner, List.of());
        assertThat(denied.statusCode()).isEqualTo(429);
        assertThat(denied.headers().firstValue("Retry-After")).contains("60");
    }

    @Test void nativeHistoryPaginatesOnlyOwnerRecordsWithStrictCursorAndBody() throws Exception {
        Login owner = login(), other = loginOther();
        var ids = new ArrayList<String>();
        for (int index = 0; index < 21; index++) {
            String id = UUID.randomUUID().toString();
            ids.add(id);
            assertThat(journey("POST", "", "{\"id\":\"" + id + "\",\"kind\":\"trip\"}", owner, List.of()).statusCode()).isEqualTo(200);
            assertThat(journey("POST", "/" + id + "/complete", "{}", owner, List.of()).statusCode()).isEqualTo(200);
        }
        String foreign = UUID.randomUUID().toString();
        assertThat(journey("POST", "", "{\"id\":\"" + foreign + "\",\"kind\":\"commute\"}", other, List.of()).statusCode()).isEqualTo(200);
        var latest = journey("POST", "/history", "{}", owner, List.of());
        assertThat(latest.statusCode()).isEqualTo(200);
        assertThat(latest.body()).doesNotContain(foreign, owner.accountId());
        List<String> firstIds = JsonPath.read(latest.body(), "$.journeys[*].id");
        assertThat(firstIds).hasSize(20).doesNotHaveDuplicates();
        String lastId = JsonPath.read(latest.body(), "$.next.id");
        assertThat(lastId).isEqualTo(firstIds.getLast());
        String startedAt = JsonPath.read(latest.body(), "$.next.startedAt");
        String canonical = new java.time.format.DateTimeFormatterBuilder().appendInstant(6)
                .toFormatter().format(java.time.Instant.parse(startedAt));
        String before = "{\"before\":{\"startedAt\":\"" + canonical + "\",\"id\":\"" + lastId + "\"}}";
        var earlier = journey("POST", "/history", before, owner, List.of());
        assertThat(earlier.statusCode()).isEqualTo(200);
        List<String> olderIds = JsonPath.read(earlier.body(), "$.journeys[*].id");
        assertThat(olderIds).hasSize(1).doesNotContainAnyElementsOf(firstIds);
        Object terminalCursor = JsonPath.read(earlier.body(), "$.next");
        assertThat(terminalCursor).isNull();
        String otherPage = journey("POST", "/history", "{}", other, List.of()).body();
        assertThat(otherPage).contains(foreign);
        for (String id : ids) assertThat(otherPage).doesNotContain(id);
        assertThat(journey("POST", "/history", "{}", null, List.of()).statusCode()).isEqualTo(401);
        assertThat(journey("POST", "/history", "{}", owner,
                List.<String[]>of(new String[] {"X-Routiqo-Account", other.accountId()})).statusCode()).isEqualTo(401);
        for (String invalid : List.of("{\"extra\":1}", "{\"before\":null}",
                "{\"before\":{\"startedAt\":\"" + canonical + "\"}}",
                "{\"before\":{\"startedAt\":\"" + canonical + "\",\"id\":\"" + lastId + "\",\"extra\":1}}",
                "{\"before\":{\"startedAt\":\"2026-09-12T24:00:00.000000Z\",\"id\":\"" + lastId + "\"}}",
                "{\"before\":{\"startedAt\":\"2026-09-12T12:00:00Z\",\"id\":\"" + lastId + "\"}}",
                "{\"before\":{},\"before\":{}}")) {
            assertThat(journey("POST", "/history", invalid, owner, List.of()).statusCode()).isEqualTo(400);
        }
        assertThat(journey("POST", "/history?limit=1", "{}", owner, List.of()).statusCode()).isEqualTo(403);
        assertThat(journey("POST", "/history", "{}", owner,
                List.<String[]>of(new String[] {"Cookie", "routiqo_session=forbidden"})).statusCode()).isEqualTo(403);
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
        assertThat(nonce).hasSize(64);
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

    @Test void forwardedForIsIgnoredWithoutTrustedBalancerRanges() throws Exception {
        for (int i = 0; i < 10; i++) {
            var response = request("POST", "google/challenge", "{}", null,
                    List.<String[]>of(new String[] {"X-Forwarded-For", "198.51.100." + i}));
            assertThat(response.statusCode()).isEqualTo(200);
        }
        var spoofed = request("POST", "google/challenge", "{}", null,
                List.<String[]>of(new String[] {"X-Forwarded-For", "203.0.113.200"}));
        assertThat(spoofed.statusCode()).isEqualTo(429);
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
