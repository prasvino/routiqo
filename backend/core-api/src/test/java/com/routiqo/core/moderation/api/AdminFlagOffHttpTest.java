package com.routiqo.core.moderation.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0075: the admin base opens only for the exact value "true", even when feature flags are on. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=consumer-client.apps.googleusercontent.com",
    "ROUTIQO_ADMIN_GOOGLE_CLIENT_ID=admin-client.apps.googleusercontent.com",
    "ROUTIQO_WEB_ORIGIN=http://localhost:3000", "ROUTIQO_ADMIN_ORIGIN=http://localhost:3001",
    "ROUTIQO_AUTH_SECURE_COOKIES=false", "ROUTIQO_ADMIN_ENABLED=TRUE", "ROUTIQO_V3_ADMIN_ENABLED=true",
    "ROUTIQO_SPOTS_ADMIN_ENABLED=true", "ROUTIQO_SPOTS_GRANT_ADMIN_ENABLED=true"
})
@ActiveProfiles({"persistence", "google-auth", "web-auth"})
class AdminFlagOffHttpTest {
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("ROUTIQO_AUTH_RATE_SECRET", () -> "admin-flag-off-test-rate-secret-32-chars");
    }
    @Value("${local.server.port}") int port;

    @Test void noAdminPathAnswersWithoutTheExactAdminFlag() throws Exception {
        var client = HttpClient.newHttpClient();
        for (String path : new String[] {"auth/csrf", "auth/session", "spots/reports", "spot-grants/me",
                "community-traffic/reports"}) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/admin/" + path))
                    .GET().build();
            int status = client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
            assertThat(status).as(path).isIn(401, 403, 404);
        }
    }
}
