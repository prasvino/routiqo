package com.routiqo.core.identity.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com"
})
@ActiveProfiles({"persistence", "google-auth", "native-auth"})
@Import(NativeAuthHttpTest.TestIdentity.class)
class NativeAuthOnlyHttpTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        NativeAuthHttpTest.databaseProperties(properties);
    }

    @Value("${local.server.port}") int port;

    @Test void nativeProfileActivatesWithoutBrowserAuth() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/native/auth/google/challenge"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"binding\"");
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(
                value -> assertThat(value).contains("no-store"));
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();

        var browser = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/csrf"))
                .GET().build();
        assertThat(HttpClient.newHttpClient().send(browser, HttpResponse.BodyHandlers.ofString()).statusCode()).isIn(401, 403);
    }
}
