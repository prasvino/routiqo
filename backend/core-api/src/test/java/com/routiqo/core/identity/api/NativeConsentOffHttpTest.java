package com.routiqo.core.identity.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import com.routiqo.core.privacy.api.NativeConsentController;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com"
})
@ActiveProfiles({"persistence", "google-auth", "native-auth"})
@Import(NativeAuthHttpTest.TestIdentity.class)
class NativeConsentOffHttpTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        NativeAuthHttpTest.databaseProperties(properties);
    }

    @Value("${local.server.port}") int port;
    @Autowired ApplicationContext context;

    @Test void nativeConsentRemainsDeniedByDefaultWithoutAControllerOrSecurityGrant() throws Exception {
        assertThat(context.getBeansOfType(NativeConsentController.class)).isEmpty();
        String path = "http://localhost:" + port + "/api/v1/native/journeys/" + UUID.randomUUID() + "/consent";
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }
}
