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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=consumer-client.apps.googleusercontent.com",
    "ROUTIQO_ADMIN_GOOGLE_CLIENT_ID=admin-client.apps.googleusercontent.com",
    "ROUTIQO_WEB_ORIGIN=http://localhost:3000", "ROUTIQO_ADMIN_ORIGIN=http://localhost:3001",
    "ROUTIQO_AUTH_SECURE_COOKIES=false", "ROUTIQO_V3_ADMIN_ENABLED=true",
    "ROUTIQO_V3_GRANT_ADMIN_ENABLED=true"
})
@ActiveProfiles({"persistence", "google-auth", "web-auth"})
@Import(AdminTrafficGrantHttpTest.FakeIdentity.class)
class AdminTrafficGrantHttpTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("ROUTIQO_AUTH_RATE_SECRET", () -> "grant-http-test-rate-secret-32-characters");
    }
    @TestConfiguration static class FakeIdentity {
        @Bean @Primary AdminSessionService fakeAdminSessionService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
            return new AdminSessionService(jdbc, manager,
                    (token, nonce) -> {
                        if (!token.equals(nonce)) throw new SecurityException("Invalid test credential");
                        return new GoogleIdentityVerifier.Identity("google", "grant-http-subject");
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
    @Test void rootGrantIsSeparateAndExactTargetMutationsRequireOriginCsrfAndBoundedBody() throws Exception {
        String csrf = JsonPath.read(call("GET", "auth/csrf", "", null, null).body(), "$.token");
        UUID actor = UUID.randomUUID(), target = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, 'grant-http-subject')", actor);
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)", target, "target-" + target);
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
                VALUES (?, 'traffic_review', ?, ?)
                """, actor, Timestamp.from(now.minusSeconds(10)), Timestamp.from(now.plusSeconds(600)));
        String challengeBody = call("POST", "auth/google/challenge", "{}", csrf, "http://localhost:3001").body();
        String challenge = JsonPath.read(challengeBody, "$.id"), nonce = JsonPath.read(challengeBody, "$.nonce");
        assertThat(call("POST", "auth/google/exchange", "{\"challengeId\":\"" + challenge
                + "\",\"idToken\":\"" + nonce + "\"}", csrf, "http://localhost:3001").statusCode()).isEqualTo(200);
        assertThat(call("GET", "traffic-grants/me", "", null, null).body()).contains("\"canManageGrants\":false");
        assertThat(call("GET", "traffic-grants/" + target, "", null, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "traffic-grants/" + UUID.randomUUID(), "", null, null).statusCode()).isEqualTo(403);
        UUID disabled = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject, enabled) VALUES (?, ?, FALSE)", disabled, "disabled-" + disabled);
        assertThat(call("GET", "traffic-grants/" + disabled, "", null, null).statusCode()).isEqualTo(403);
        jdbc.update("""
                INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
                VALUES (?, 'traffic_grant_admin', ?, ?)
                """, actor, Timestamp.from(now.minusSeconds(10)), Timestamp.from(now.plusSeconds(600)));
        assertThat(call("GET", "traffic-grants/me", "", null, null).body()).contains("\"canManageGrants\":true");
        String issue = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"permission\":\"traffic_suppress\","
                + "\"durationMinutes\":15,\"reason\":\"OPERATOR_TRIAL\"}";
        assertThat(call("POST", "traffic-grants/" + target + "/issue", issue, null, "http://localhost:3001").statusCode())
                .isEqualTo(403);
        assertThat(call("POST", "traffic-grants/" + target + "/issue", issue, csrf, "http://localhost:3000").statusCode())
                .isEqualTo(403);
        assertThat(call("POST", "traffic-grants/" + target + "/issue", "{\"padding\":\"" + "a".repeat(21000)
                + "\"}", csrf, "http://localhost:3001").statusCode()).isEqualTo(413);
        assertThat(call("POST", "traffic-grants/" + target + "/issue", issue, csrf, "http://localhost:3001").statusCode())
                .isEqualTo(200);
        assertThat(call("GET", "traffic-grants/" + target, "", null, null).body()).contains("traffic_suppress");
        assertThat(call("GET", "traffic-grants/" + UUID.randomUUID(), "", null, null).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE routiqo_account SET enabled = FALSE WHERE id = ?", target);
        assertThat(call("GET", "traffic-grants/" + target, "", null, null).statusCode()).isEqualTo(404);
    }
}
