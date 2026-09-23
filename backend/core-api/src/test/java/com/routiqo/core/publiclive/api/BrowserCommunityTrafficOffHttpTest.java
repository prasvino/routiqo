package com.routiqo.core.publiclive.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com",
    "ROUTIQO_WEB_ORIGIN=http://localhost:3000",
    "ROUTIQO_AUTH_SECURE_COOKIES=false",
    "ROUTIQO_VALHALLA_ORIGIN=http://127.0.0.1:18002",
    "ROUTIQO_PHOTON_ORIGIN=http://127.0.0.1:12322",
    "ROUTIQO_ROUTING_REGION_WEST=78", "ROUTIQO_ROUTING_REGION_SOUTH=11",
    "ROUTIQO_ROUTING_REGION_EAST=81", "ROUTIQO_ROUTING_REGION_NORTH=14",
    "ROUTIQO_COMMUNITY_TRAFFIC_V3_ENABLED=false"
})
@ActiveProfiles({"persistence", "google-auth", "web-auth", "routing"})
class BrowserCommunityTrafficOffHttpTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("ROUTIQO_AUTH_RATE_SECRET", () -> "v3-off-http-test-secret-32-characters");
    }

    @Value("${local.server.port}") int port;

    @Test void disabledV3RoutesAreDeniedByBrowserSecurityChain() throws Exception {
        UUID journey = UUID.randomUUID(), command = UUID.randomUUID(), ref = UUID.randomUUID();
        var client = HttpClient.newHttpClient();
        for (String path : new String[] {
                "/api/v1/community-shares",
                "/api/v1/journeys/" + journey + "/community-traffic"
        }) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .GET().build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isIn(401, 403);
        }
        // The disabled surface is protected even before an authenticated account exists.
        for (String path : new String[] {
                "/api/v1/journeys/" + journey + "/signals/" + command + "/community-share",
                "/api/v1/journeys/" + journey + "/signals/" + command + "/community-share/stop",
                "/api/v1/journeys/" + journey + "/community-traffic/" + ref + "/reports"
        }) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Origin", "https://attacker.invalid")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(403);
        }
    }
}
