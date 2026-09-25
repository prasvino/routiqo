package com.routiqo.core.publiclive.api;

import com.routiqo.core.moderation.api.AdminTrafficController;
import com.routiqo.core.moderation.api.AdminTrafficGrantController;
import com.routiqo.core.publiclive.infrastructure.CommunityTrafficV3JobConfiguration;
import com.routiqo.core.publiclive.infrastructure.CommunityTrafficV3MaintenanceConfiguration;
import com.routiqo.core.publiclive.infrastructure.JdbcCommunityTrafficPublisherV3;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0065: with ambiguous flag values (subclasses set them), no community, public-intent or V3 admin
 * bean exists and the browser security chain keeps every community path denied rather than open.
 */
abstract class CommunityLiveFlagFailClosedHttpTest {
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("ROUTIQO_AUTH_RATE_SECRET", () -> "v3-flag-closed-test-secret-32-characters");
    }

    @Autowired ApplicationContext context;
    @Value("${local.server.port}") int port;

    @Test void noCommunityPublicOrModerationBeanLoads() {
        for (Class<?> type : new Class<?>[] {
                BrowserCommunityTrafficV3Controller.class, BrowserCommunityTrafficController.class,
                BrowserPublicSignalIntentController.class, BrowserPublicSignalIntentListController.class,
                JdbcCommunityTrafficPublisherV3.class, CommunityTrafficV3JobConfiguration.class,
                CommunityTrafficV3MaintenanceConfiguration.class, AdminTrafficController.class,
                AdminTrafficGrantController.class })
            assertThat(context.getBeanNamesForType(type)).as(type.getSimpleName()).isEmpty();
    }

    @Test void securityChainStaysClosedInsteadOfOpeningAnUnservedPath() throws Exception {
        UUID journey = UUID.randomUUID(), ref = UUID.randomUUID();
        var client = HttpClient.newHttpClient();
        for (String path : new String[] {
                "/api/v1/community-shares",
                "/api/v1/journeys/" + journey + "/community-traffic",
                "/api/v1/admin/community-traffic/reports" }) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
            // 404 would mean the chain permitted the path; a closed chain denies it.
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .as(path).isIn(401, 403);
        }
        var report = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/v1/journeys/" + journey + "/community-traffic/" + ref + "/reports"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertThat(client.send(report, HttpResponse.BodyHandlers.ofString()).statusCode()).isIn(401, 403);
    }
}
