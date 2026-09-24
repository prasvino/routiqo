package com.routiqo.core.identity.api;

import com.routiqo.core.routeupdate.api.NativeRouteContextController;
import com.routiqo.core.routeupdate.application.RouteBindingService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com",
    "ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED=true",
    "ROUTIQO_ROUTING_REGION_WEST=-1", "ROUTIQO_ROUTING_REGION_SOUTH=-1",
    "ROUTIQO_ROUTING_REGION_EAST=1", "ROUTIQO_ROUTING_REGION_NORTH=1"
})
@ActiveProfiles({"persistence", "google-auth", "native-auth", "routing"})
@Import(NativeAuthHttpTest.TestIdentity.class)
class NativeRouteContextOffHttpTest {
    private static final Path CATALOG = catalog();
    private static Path catalog() {
        try {
            Path path = Files.createTempFile("routiqo-native-route-off-", ".json");
            Files.writeString(path, """
                    {"version":"00000000-0000-4000-8000-000000000101","anchors":[
                    {"id":"00000000-0000-4000-8000-000000000102","longitude":0,"latitude":0,
                     "categories":["TRAFFIC"],"displayLabel":"Central Junction"}]}
                    """, StandardCharsets.UTF_8);
            return path;
        } catch (Exception failure) { throw new IllegalStateException("Fixture unavailable", failure); }
    }
    @AfterAll static void clean() throws Exception { Files.deleteIfExists(CATALOG); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        NativeAuthHttpTest.databaseProperties(registry);
        registry.add("ROUTIQO_VALHALLA_ORIGIN", () -> "http://127.0.0.1:18002");
        registry.add("ROUTIQO_PHOTON_ORIGIN", () -> "http://127.0.0.1:12322");
        registry.add("ROUTIQO_LIVE_ANCHOR_CATALOG_PATH", CATALOG::toString);
    }
    @Value("${local.server.port}") int port;
    @Autowired ApplicationContext context;

    @Test void configuredResolverAndBinderDoNotExposeFlaggedOffNativeLeaves() throws Exception {
        assertThat(context.getBeansOfType(RouteBindingService.class)).hasSize(1);
        assertThat(context.getBeansOfType(NativeRouteContextController.class)).isEmpty();
        String path = "http://localhost:" + port + "/api/v1/native/journeys/" + UUID.randomUUID()
                + "/route-context";
        var client = HttpClient.newHttpClient();
        for (String method : new String[] {"GET", "POST"}) {
            var builder = HttpRequest.newBuilder(URI.create(path));
            if (method.equals("GET")) builder.GET();
            else builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"));
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).isEmpty();
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        }
    }

    @Test void enabledExposureCannotStartWithoutBindingAuthority() {
        new ApplicationContextRunner()
                .withUserConfiguration(NativeRouteContextController.class)
                .withPropertyValues("spring.profiles.active=native-auth,routing,persistence",
                        "ROUTIQO_NATIVE_LIVE_ROUTE_BINDING_API_ENABLED=true")
                .run(result -> assertThat(result).hasFailed());
    }
}
