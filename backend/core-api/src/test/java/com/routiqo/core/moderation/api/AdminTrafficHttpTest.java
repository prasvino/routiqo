package com.routiqo.core.moderation.api;

import com.jayway.jsonpath.JsonPath;
import com.routiqo.core.identity.application.GoogleIdentityVerifier;
import com.routiqo.core.moderation.infrastructure.AdminSessionService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=consumer-client.apps.googleusercontent.com",
    "ROUTIQO_ADMIN_GOOGLE_CLIENT_ID=admin-client.apps.googleusercontent.com",
    "ROUTIQO_WEB_ORIGIN=http://localhost:3000",
    "ROUTIQO_ADMIN_ORIGIN=http://localhost:3001",
    "ROUTIQO_AUTH_SECURE_COOKIES=false",
    "ROUTIQO_ADMIN_ENABLED=true", "ROUTIQO_V3_ADMIN_ENABLED=true"
})
@ActiveProfiles({"persistence", "google-auth", "web-auth"})
@Import(AdminTrafficHttpTest.FakeIdentity.class)
class AdminTrafficHttpTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("ROUTIQO_AUTH_RATE_SECRET", () -> "admin-http-test-rate-secret-32-characters");
    }
    @TestConfiguration static class FakeIdentity {
        @Bean @Primary AdminSessionService fakeAdminSessionService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
            return new AdminSessionService(jdbc, manager,
                    (token, nonce) -> {
                        if (!token.equals(nonce)) throw new SecurityException("Invalid test credential");
                        return new GoogleIdentityVerifier.Identity("google", "admin-http-subject");
                    });
        }
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    HttpClient client;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM auth_rate_bucket");
        client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    HttpResponse<String> call(String method, String path, String body, String csrf, String origin) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/admin/" + path));
        if (origin != null) builder.header("Origin", origin);
        if (csrf != null) builder.header("X-XSRF-TOKEN", csrf);
        if (method.equals("POST")) builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        else builder.GET();
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    String csrf() throws Exception {
        var response = call("GET", "auth/csrf", "", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.token");
    }
    @Test void adminOriginCsrfGrantAndSessionAreSeparate() throws Exception {
        String csrf = csrf();
        assertThat(call("GET", "traffic-grants/me", "", null, null).statusCode()).isEqualTo(401);
        assertThat(call("POST", "auth/google/challenge", "{}", null, "http://localhost:3001").statusCode())
                .isEqualTo(403);
        assertThat(call("POST", "auth/google/challenge", "{}", csrf, "http://localhost:3000").statusCode())
                .isEqualTo(403);
        assertThat(call("GET", "auth/session", "", null, null).statusCode()).isEqualTo(401);
        var forged = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/admin/auth/session"))
                .header("Cookie", "routiqo_session=" + "x".repeat(43)).GET().build();
        assertThat(HttpClient.newHttpClient().send(forged, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
        var challenge = call("POST", "auth/google/challenge", "{}", csrf, "http://localhost:3001");
        assertThat(challenge.statusCode()).isEqualTo(200);
        assertThat(challenge.headers().allValues("Set-Cookie")).anySatisfy(cookie ->
                assertThat(cookie).contains("routiqo_admin_binding=", "HttpOnly", "SameSite=Strict"));
        String id = JsonPath.read(challenge.body(), "$.id"), nonce = JsonPath.read(challenge.body(), "$.nonce");
        String body = "{\"challengeId\":\"" + id + "\",\"idToken\":\"" + nonce + "\"}";
        assertThat(call("POST", "auth/google/exchange", body, csrf, "http://localhost:3001").statusCode())
                .isEqualTo(401); // No account creation.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM routiqo_account WHERE google_subject = 'admin-http-subject'",
                Integer.class)).isZero();
        UUID operator = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, 'admin-http-subject')", operator);
        assertThat(call("POST", "auth/google/exchange", body, csrf, "http://localhost:3001").statusCode())
                .isEqualTo(401); // Existing account alone is insufficient.
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
                VALUES (?, 'traffic_review', ?, ?)
                """, operator, Timestamp.from(now.minusSeconds(10)), Timestamp.from(now.plusSeconds(600)));
        var exchange = call("POST", "auth/google/exchange", body, csrf, "http://localhost:3001");
        assertThat(exchange.statusCode()).isEqualTo(200);
        assertThat(exchange.body()).contains(operator.toString()).doesNotContain("credential", "idToken");
        assertThat(call("GET", "auth/session", "", null, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "community-traffic/reports?limit=20", "", null, null).statusCode())
                .isEqualTo(200);
        jdbc.update("UPDATE moderation_operator_grant SET expires_at = ? WHERE operator_id = ? AND permission = 'traffic_review'",
                Timestamp.from(Instant.now().minusSeconds(1)), operator);
        assertThat(call("GET", "auth/session", "", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "community-traffic/reports", "", null, null).statusCode()).isEqualTo(401);
        assertThat(call("POST", "auth/logout", "{}", csrf, "http://localhost:3001").statusCode())
                .isEqualTo(204);
        jdbc.update("UPDATE moderation_operator_grant SET expires_at = ? WHERE operator_id = ? AND permission = 'traffic_review'",
                Timestamp.from(Instant.now().plusSeconds(600)), operator);
        assertThat(call("GET", "auth/session", "", null, null).statusCode()).isEqualTo(401);
    }
    @Test void adminOriginCannotEqualConsumerOrigin() {
        assertThatThrownBy(() -> new AdminAccessConfiguration()
                .adminSettings("http://localhost:3000", "http://localhost:3000", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminAccessConfiguration()
                .adminSettings("https://Admin.Example.test:443", "https://admin.example.test", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminAccessConfiguration()
                .adminSettings("http://localhost:80", "http://localhost", false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
