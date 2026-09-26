package com.routiqo.core.identity.api;

import com.routiqo.core.spot.api.NativeSpotController;
import com.routiqo.core.spot.application.SpotActivityService;
import com.routiqo.core.spot.domain.SpotCatalog;
import com.routiqo.core.spot.infrastructure.SpotsConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.MapPropertySource;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.journey.application.ActiveJourneyReader;
import com.routiqo.core.routing.domain.RoutingRegion;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** The Spots flag enables only the exact lowercase value {@code true}; anything else leaves both leaves off. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com",
    "ROUTIQO_SPOTS_API_ENABLED=TRUE",
    "ROUTIQO_SPOT_CATALOG_PATH=/nonexistent/spots.json",
    "ROUTIQO_ROUTING_REGION_WEST=-1", "ROUTIQO_ROUTING_REGION_SOUTH=-1",
    "ROUTIQO_ROUTING_REGION_EAST=1", "ROUTIQO_ROUTING_REGION_NORTH=1",
    "ROUTIQO_VALHALLA_ORIGIN=http://127.0.0.1:18002", "ROUTIQO_PHOTON_ORIGIN=http://127.0.0.1:12322"
})
@ActiveProfiles({"persistence", "google-auth", "native-auth", "routing"})
@Import(NativeAuthHttpTest.TestIdentity.class)
class NativeSpotOffHttpTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        NativeAuthHttpTest.databaseProperties(registry);
    }
    @Value("${local.server.port}") int port;
    @Autowired ApplicationContext context;

    @Test void mixedCaseFlagLeavesCatalogUnloadedAndBothLeavesForbidden() throws Exception {
        assertThat(context.getBeansOfType(NativeSpotController.class)).isEmpty();
        assertThat(context.getBeansOfType(SpotCatalog.class)).isEmpty();
        assertThat(context.getBeansOfType(SpotActivityService.class)).isEmpty();
        var client = HttpClient.newHttpClient();
        for (String[] leaf : List.of(new String[] {"GET", "catalog"}, new String[] {"POST", "activity"})) {
            var builder = HttpRequest.newBuilder(URI.create(
                    "http://localhost:" + port + "/api/v1/native/spots/" + leaf[1]))
                    .header("Authorization", "Bearer " + "A".repeat(43))
                    .header("X-Routiqo-Account", "00000000-0000-4000-8000-000000000001");
            if (leaf[0].equals("GET")) builder.GET();
            else builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"spotIds\":[]}"));
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).isEmpty();
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        }
    }

    @Test void onlyTheExactLowercaseValueEnablesTheConfiguration() {
        for (String value : new String[] {"TRUE", "True", "1", "yes", "on", " true", "true ", ""}) {
            new ApplicationContextRunner()
                    .withUserConfiguration(SpotsConfiguration.class)
                    .withPropertyValues("spring.profiles.active=native-auth,routing,persistence")
                    // Raw, untrimmed value as an environment variable would supply it.
                    .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                            new MapPropertySource("flag", Map.of("ROUTIQO_SPOTS_API_ENABLED", value))))
                    .run(result -> {
                        assertThat(result).hasNotFailed();
                        assertThat(result).doesNotHaveBean(SpotCatalog.class);
                    });
        }
    }

    private static ApplicationContextRunner configured(RoutingRegion region, String catalogPath) {
        return new ApplicationContextRunner()
                .withUserConfiguration(SpotsConfiguration.class)
                .withBean(RoutingRegion.class, () -> region)
                .withBean(AuthRateGate.class, () -> (key, category, limit) -> true)
                .withBean(ActiveJourneyReader.class, () -> owner -> true)
                .withBean(com.routiqo.core.moderation.application.BlockedAccountsReader.class,
                        () -> viewer -> java.util.Set.of())
                .withBean(org.springframework.jdbc.core.JdbcTemplate.class,
                        () -> new org.springframework.jdbc.core.JdbcTemplate(
                                new org.springframework.jdbc.datasource.SimpleDriverDataSource()))
                .withPropertyValues("spring.profiles.active=native-auth,routing,persistence",
                        "ROUTIQO_SPOTS_API_ENABLED=true", "ROUTIQO_SPOT_CATALOG_PATH=" + catalogPath);
    }

    @Test void enabledFlagStartsOnlyWithAValidInRegionCatalog(@TempDir Path directory) throws Exception {
        Path valid = Files.writeString(directory.resolve("spots.json"), NativeSpotHttpTest.catalogJson());
        configured(new RoutingRegion(-1, -1, 1, 1), valid.toString())
                .run(result -> assertThat(result).hasNotFailed().hasSingleBean(SpotCatalog.class));
        for (var failing : List.of(
                configured(new RoutingRegion(10, 10, 11, 11), valid.toString()),
                configured(new RoutingRegion(-1, -1, 1, 1), directory.resolve("missing.json").toString()),
                configured(new RoutingRegion(-1, -1, 1, 1), "")))
            failing.run(result -> assertThat(result).hasFailed().getFailure()
                    .rootCause().hasMessage("Spot catalog is not configured"));
        new ApplicationContextRunner()
                .withUserConfiguration(NativeSpotController.class)
                .withPropertyValues("spring.profiles.active=native-auth,routing,persistence",
                        "ROUTIQO_SPOTS_API_ENABLED=true")
                .run(result -> assertThat(result).hasFailed());
    }
}
