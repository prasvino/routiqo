package com.routiqo.core.identity.api;

import com.routiqo.core.spot.api.NativeSpotController;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
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

/** Flag on without the routing profile: no controller, so the guard must not admit the paths either. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com",
    "ROUTIQO_SPOTS_API_ENABLED=true"
})
@ActiveProfiles({"persistence", "google-auth", "native-auth"})
@Import(NativeAuthHttpTest.TestIdentity.class)
class NativeSpotProfileHttpTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        NativeAuthHttpTest.databaseProperties(properties);
    }
    @Value("${local.server.port}") int port;
    @Autowired ApplicationContext context;

    static void assertSpotLeavesForbidden(int port) throws Exception {
        var client = HttpClient.newHttpClient();
        for (String[] leaf : List.of(new String[] {"GET", "catalog"}, new String[] {"POST", "activity"})) {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/native/spots/" + leaf[1]))
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

    @Test void flagWithoutSpotProfilesLeavesBothLeavesForbiddenNotUnhandled() throws Exception {
        assertThat(context.getBeansOfType(NativeSpotController.class)).isEmpty();
        assertSpotLeavesForbidden(port);
    }
}
