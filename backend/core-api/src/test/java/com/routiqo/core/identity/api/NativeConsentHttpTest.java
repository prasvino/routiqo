package com.routiqo.core.identity.api;

import com.jayway.jsonpath.JsonPath;
import com.routiqo.core.identity.application.AccountWriteAuthority;
import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.infrastructure.JdbcAccountWriteAuthority;
import com.routiqo.core.identity.infrastructure.JdbcAuthRateGate;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com",
    "ROUTIQO_NATIVE_LIVE_CONSENT_API_ENABLED=true"
})
@ActiveProfiles({"persistence", "google-auth", "native-auth"})
@Import({NativeAuthHttpTest.TestIdentity.class, NativeConsentHttpTest.ControlledFailures.class})
class NativeConsentHttpTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        NativeAuthHttpTest.databaseProperties(properties);
    }

    @TestConfiguration static class ControlledFailures {
        @Bean @Qualifier("nativeConsentRateFailure") AtomicBoolean rateFailure() { return new AtomicBoolean(); }
        @Bean @Qualifier("nativeConsentGuardRateFailure") AtomicBoolean guardRateFailure() { return new AtomicBoolean(); }
        @Bean @Qualifier("nativeConsentAuthorityFailure") AtomicBoolean authorityFailure() { return new AtomicBoolean(); }

        @Bean @Primary AuthRateGate controlledRates(JdbcTemplate jdbc,
                @Value("${ROUTIQO_AUTH_RATE_SECRET}") String secret,
                @Qualifier("nativeConsentRateFailure") AtomicBoolean fail,
                @Qualifier("nativeConsentGuardRateFailure") AtomicBoolean guardFail) {
            var delegate = new JdbcAuthRateGate(jdbc, secret,
                    Clock.fixed(java.time.Instant.parse("2026-09-24T00:00:00Z"), java.time.ZoneOffset.UTC));
            return (identity, category, limit) -> {
                if ((fail.get() && category.startsWith("consent-"))
                        || (guardFail.get() && category.equals("native-other")))
                    throw new IllegalStateException("Synthetic rate store failure");
                return delegate.allow(identity, category, limit);
            };
        }

        @Bean @Primary AccountWriteAuthority controlledAuthority(JdbcTemplate jdbc, DataSource source,
                @Qualifier("nativeConsentAuthorityFailure") AtomicBoolean fail) {
            var delegate = new JdbcAccountWriteAuthority(jdbc, new DataSourceTransactionManager(source));
            return new AccountWriteAuthority() {
                @Override public <T> T withEnabledAccount(UUID actorId, Work<T> work) {
                    if (fail.get()) throw new AccountWriteUnavailable();
                    return delegate.withEnabledAccount(actorId, work);
                }
            };
        }
    }

    record Login(String account, String credential) {}
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthRateGate rates;
    @Autowired @Qualifier("nativeConsentRateFailure") AtomicBoolean rateFailure;
    @Autowired @Qualifier("nativeConsentGuardRateFailure") AtomicBoolean guardRateFailure;
    @Autowired @Qualifier("nativeConsentAuthorityFailure") AtomicBoolean authorityFailure;
    HttpClient client;

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM auth_rate_bucket");
        jdbc.update("DELETE FROM login_challenge");
        jdbc.update("DELETE FROM routiqo_account");
        rateFailure.set(false);
        guardRateFailure.set(false);
        authorityFailure.set(false);
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpResponse<String> send(String method, String path, String body, Login identity,
            List<String[]> extra) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/native/" + path));
        if (identity != null) {
            builder.header("Authorization", "Bearer " + identity.credential());
            builder.header("X-Routiqo-Account", identity.account());
        }
        for (var header : extra) builder.header(header[0], header[1]);
        if (method.equals("POST")) {
            if (extra.stream().noneMatch(header -> header[0].equalsIgnoreCase("Content-Type")))
                builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
        } else if (method.equals("GET")) builder.GET();
        else builder.method(method, HttpRequest.BodyPublishers.noBody());
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.headers().allValues("Access-Control-Allow-Origin")).isEmpty();
        assertThat(response.headers().allValues("Location")).isEmpty();
        return response;
    }

    Login login(boolean other) throws Exception {
        var challenge = send("POST", "auth/google/challenge", "{}", null, List.of());
        assertThat(challenge.statusCode()).isEqualTo(200);
        String id = JsonPath.read(challenge.body(), "$.id");
        String nonce = JsonPath.read(challenge.body(), "$.nonce");
        String binding = JsonPath.read(challenge.body(), "$.binding");
        String token = other ? nonce + "-other" : nonce;
        var exchange = send("POST", "auth/google/exchange", NativeAuthHttpTest.exchangeBody(id, binding, token),
                null, List.of());
        assertThat(exchange.statusCode()).isEqualTo(200);
        return new Login(JsonPath.read(exchange.body(), "$.accountId"),
                JsonPath.read(exchange.body(), "$.credential"));
    }

    String start(Login login) throws Exception {
        String id = UUID.randomUUID().toString();
        assertThat(send("POST", "journeys", "{\"id\":\"" + id + "\",\"kind\":\"trip\"}",
                login, List.of()).statusCode()).isEqualTo(200);
        return id;
    }

    HttpResponse<String> consent(Login login, String journey, String method, String body) throws Exception {
        return send(method, "journeys/" + journey + "/consent", body, login, List.of());
    }

    static String intent(String generation, boolean sharing) {
        return "{\"expectedGeneration\":\"" + generation + "\",\"sharing\":" + sharing + "}";
    }

    static void empty(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).isEmpty();
    }

    @Test void ownerIntentOrderingAndCompletionUseRealConsentAuthority() throws Exception {
        Login owner = login(false), other = login(true);
        String trip = start(owner);
        var initial = consent(owner, trip, "GET", "");
        assertThat(initial.statusCode()).isEqualTo(200);
        assertThat(initial.body()).contains("\"journeyId\":\"" + trip + "\"", "\"generation\":\"0\"",
                "\"sharing\":false", "\"journeyActive\":true");
        assertThat(initial.body()).doesNotContain(owner.account(), other.account());
        empty(consent(other, trip, "GET", ""), 404);
        empty(consent(other, trip, "POST", intent("0", true)), 404);
        var enabled = consent(owner, trip, "POST", intent("0", true));
        assertThat(enabled.statusCode()).isEqualTo(200);
        assertThat(enabled.body()).contains("\"generation\":\"1\"", "\"sharing\":true");
        empty(consent(owner, trip, "POST", intent("0", true)), 409);
        var stopped = consent(owner, trip, "POST", intent("0", false));
        assertThat(stopped.statusCode()).isEqualTo(200);
        assertThat(stopped.body()).contains("\"generation\":\"2\"", "\"sharing\":false");
        empty(consent(owner, trip, "POST", intent("3", false)), 409);
        assertThat(send("POST", "journeys/" + trip + "/complete", "{}", owner, List.of()).statusCode()).isEqualTo(200);
        assertThat(consent(owner, trip, "GET", "").body()).contains("\"journeyActive\":false", "\"sharing\":false");
        empty(consent(owner, trip, "POST", intent("2", true)), 409);
    }

    @Test void exactMaximumGenerationAndStrictBodyAreEnforced() throws Exception {
        Login owner = login(false);
        String trip = start(owner);
        for (String body : List.of("{}", "null", "[]", "{",
                "{\"expectedGeneration\":0,\"sharing\":false}",
                "{\"expectedGeneration\":\"00\",\"sharing\":false}",
                "{\"expectedGeneration\":\"9223372036854775808\",\"sharing\":false}",
                "{\"expectedGeneration\":\"0\",\"sharing\":\"false\"}",
                "{\"expectedGeneration\":\"0\",\"sharing\":false,\"extra\":1}",
                "{\"expectedGeneration\":\"0\",\"expectedGeneration\":\"0\",\"sharing\":false}",
                intent("0", false) + "{}")) empty(consent(owner, trip, "POST", body), 400);
        empty(consent(owner, "00000000-0000-0000-0000-000000000000", "GET", ""), 400);
        empty(consent(owner, trip.toUpperCase(), "GET", ""), 400);
        empty(consent(owner, "not-a-uuid", "GET", ""), 403);
        empty(consent(owner, trip + "?x=1", "GET", ""), 403);
        empty(send("POST", "journeys/" + trip + "/consent", intent("0", false), owner,
                List.<String[]>of(new String[] {"Content-Type", "text/plain"})), 415);
        empty(consent(owner, trip, "POST", "x".repeat(20 * 1024 + 1)), 413);
        assertThat(consent(owner, trip, "POST", intent("0", true)).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE presence_consent SET generation = ?, sharing = TRUE WHERE actor_id = ? AND journey_id = ?",
                Long.MAX_VALUE, UUID.fromString(owner.account()), UUID.fromString(trip));
        var stopped = consent(owner, trip, "POST", intent("9223372036854775807", false));
        assertThat(stopped.statusCode()).isEqualTo(200);
        assertThat(stopped.body()).contains("\"generation\":\"9223372036854775807\"", "\"sharing\":false");
        empty(consent(owner, trip, "POST", intent("9223372036854775807", true)), 409);
    }

    @Test void guardDeniesAmbientSourcesAndReportsUnavailabilityWithoutBodies() throws Exception {
        Login owner = login(false);
        String trip = start(owner);
        empty(consent(null, trip, "GET", ""), 401);
        empty(send("GET", "journeys/" + trip + "/consent", "", owner,
                List.<String[]>of(new String[] {"X-Routiqo-Account", UUID.randomUUID().toString()})), 401);
        for (String header : List.of("Cookie", "Origin", "Sec-Fetch-Site"))
            empty(send("GET", "journeys/" + trip + "/consent", "", owner,
                    List.<String[]>of(new String[] {header, "blocked"})), 403);
        empty(send("DELETE", "journeys/" + trip + "/consent", "", owner, List.of()), 403);
        empty(send("GET", "journeys/" + trip + "/consent/extra", "", owner, List.of()), 403);
        rateFailure.set(true);
        empty(consent(owner, trip, "GET", ""), 503);
        rateFailure.set(false);
        guardRateFailure.set(true);
        empty(consent(owner, trip, "GET", ""), 503);
        guardRateFailure.set(false);
        authorityFailure.set(true);
        empty(consent(owner, trip, "GET", ""), 503);
        empty(consent(owner, trip, "POST", intent("0", false)), 503);
    }

    @Test void sharedAccountQuotasAreSeparateForReadEnableAndDisable() throws Exception {
        Login owner = login(false);
        String trip = start(owner);
        for (int index = 0; index < 59; index++)
            assertThat(rates.allow(owner.account(), "consent-read-account", 60)).isTrue();
        assertThat(consent(owner, trip, "GET", "").statusCode()).isEqualTo(200);
        var readLimited = consent(owner, trip, "GET", "");
        empty(readLimited, 429);
        assertThat(readLimited.headers().firstValue("Retry-After")).contains("60");
        for (int index = 0; index < 9; index++)
            assertThat(rates.allow(owner.account(), "consent-enable-account", 10)).isTrue();
        assertThat(consent(owner, trip, "POST", intent("0", true)).statusCode()).isEqualTo(200);
        empty(consent(owner, trip, "POST", intent("1", true)), 429);
        for (int index = 0; index < 19; index++)
            assertThat(rates.allow(owner.account(), "consent-disable-account", 20)).isTrue();
        assertThat(consent(owner, trip, "POST", intent("1", false)).statusCode()).isEqualTo(200);
        empty(consent(owner, trip, "POST", intent("2", false)), 429);
    }
}
