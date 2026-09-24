package com.routiqo.core.identity.api;

import com.routiqo.core.routing.api.NativeRoutingController;
import com.routiqo.core.routing.application.PlaceProvider;
import com.routiqo.core.routing.application.RouteProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com"
})
@ActiveProfiles({"persistence", "google-auth", "native-auth", "routing"})
@Import(NativeAuthHttpTest.TestIdentity.class)
class NativeRoutingOffHttpTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        NativeAuthHttpTest.databaseProperties(properties);
        properties.add("ROUTIQO_VALHALLA_ORIGIN", () -> "http://127.0.0.1:18002");
        properties.add("ROUTIQO_PHOTON_ORIGIN", () -> "http://127.0.0.1:12322");
        properties.add("ROUTIQO_ROUTING_REGION_WEST", () -> "78");
        properties.add("ROUTIQO_ROUTING_REGION_SOUTH", () -> "11");
        properties.add("ROUTIQO_ROUTING_REGION_EAST", () -> "81");
        properties.add("ROUTIQO_ROUTING_REGION_NORTH", () -> "14");
    }

    @Value("${local.server.port}") int port;
    @Autowired ApplicationContext context;

    @Test void nativeRoutingHasNoControllerOrSecurityGrantByDefault() throws Exception {
        assertThat(context.getBeansOfType(RouteProvider.class)).hasSize(1);
        assertThat(context.getBeansOfType(PlaceProvider.class)).hasSize(1);
        assertThat(context.getBeansOfType(NativeRoutingController.class)).isEmpty();
        var client = HttpClient.newHttpClient();
        for (String path : new String[] {"routes", "routes/places"}) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/native/" + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).isEmpty();
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        }
    }
}
