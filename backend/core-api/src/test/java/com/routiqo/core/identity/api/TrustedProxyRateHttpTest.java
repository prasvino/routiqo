package com.routiqo.core.identity.api;

import com.jayway.jsonpath.JsonPath;
import com.routiqo.core.identity.application.AuthRateGate;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0074: behind a trusted balancer (here the loopback test peer) each forwarded client has its own buckets. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com",
    "ROUTIQO_WEB_ORIGIN=http://localhost:3000", "ROUTIQO_AUTH_SECURE_COOKIES=false",
    "ROUTIQO_TRUSTED_PROXY_CIDRS=127.0.0.0/8,::1/128"
})
@ActiveProfiles({"persistence", "google-auth", "web-auth", "native-auth"})
@Import(NativeAuthHttpTest.TestIdentity.class)
class TrustedProxyRateHttpTest {
    static final String FIRST = "198.51.100.7";
    static final String SECOND = "203.0.113.9";

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        NativeAuthHttpTest.databaseProperties(properties);
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthRateGate rates;
    HttpClient client;

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM auth_rate_bucket");
        jdbc.update("DELETE FROM login_challenge");
        jdbc.update("DELETE FROM routiqo_account");
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpResponse<String> nativeCall(String method, String path, String body, String credential, String forwardedFor)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/native/auth/" + path));
        if (forwardedFor != null) builder.header("X-Forwarded-For", forwardedFor);
        if (credential != null) builder.header("Authorization", "Bearer " + credential);
        if (method.equals("POST"))
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        else builder.GET();
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> challenge(String forwardedFor) throws Exception {
        return nativeCall("POST", "google/challenge", "{}", null, forwardedFor);
    }

    String login(String forwardedFor) throws Exception {
        var challenge = challenge(forwardedFor);
        assertThat(challenge.statusCode()).isEqualTo(200);
        String id = JsonPath.read(challenge.body(), "$.id");
        String nonce = JsonPath.read(challenge.body(), "$.nonce");
        String binding = JsonPath.read(challenge.body(), "$.binding");
        var exchange = nativeCall("POST", "google/exchange", NativeAuthHttpTest.exchangeBody(id, binding, nonce),
                null, forwardedFor);
        assertThat(exchange.statusCode()).isEqualTo(200);
        return JsonPath.read(exchange.body(), "$.credential");
    }

    @Test void forwardedClientsBehindTheBalancerHaveSeparateBuckets() throws Exception {
        for (int i = 0; i < 10; i++) assertThat(challenge(FIRST).statusCode()).isEqualTo(200);
        assertThat(challenge(FIRST).statusCode()).isEqualTo(429);
        assertThat(challenge(SECOND).statusCode()).isEqualTo(200);
        // Prepending a fresh address does not escape: only the balancer-appended entry is read.
        assertThat(challenge("192.0.2.44, " + FIRST).statusCode()).isEqualTo(429);
        // No usable header falls back to the balancer's own bucket, not to a fresh one.
        for (int i = 0; i < 10; i++) assertThat(challenge(null).statusCode()).isEqualTo(200);
        assertThat(challenge("garbage").statusCode()).isEqualTo(429);
    }

    @Test void signedInNativeCallsHaveA600PerAddressCeiling() throws Exception {
        String credential = login(FIRST);
        for (int i = 0; i < 599; i++) assertThat(rates.allow(SECOND, "native-other", 600)).isTrue();
        assertThat(nativeCall("GET", "session", "", credential, SECOND).statusCode()).isEqualTo(200);
        var limited = nativeCall("GET", "session", "", credential, SECOND);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).contains("60");
        assertThat(nativeCall("GET", "session", "", credential, FIRST).statusCode()).isEqualTo(200);
    }

    @Test void browserGuardUsesTheSameClientAddress() throws Exception {
        for (int i = 0; i < 10; i++) assertThat(browserChallenge(FIRST).statusCode()).isNotEqualTo(429);
        assertThat(browserChallenge(FIRST).statusCode()).isEqualTo(429);
        assertThat(browserChallenge(SECOND).statusCode()).isNotEqualTo(429);
    }

    HttpResponse<String> browserChallenge(String forwardedFor) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/google/challenge"))
                .header("Content-Type", "application/json").header("Origin", "http://localhost:3000")
                .header("X-Forwarded-For", forwardedFor).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
