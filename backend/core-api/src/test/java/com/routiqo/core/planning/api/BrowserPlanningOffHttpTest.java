package com.routiqo.core.planning.api;

import com.jayway.jsonpath.JsonPath;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/** Default configuration: the planning copy API is absent and its larger body allowance does not apply. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com",
    "ROUTIQO_WEB_ORIGIN=http://localhost:3000", "ROUTIQO_AUTH_SECURE_COOKIES=false"
})
@ActiveProfiles({"persistence", "google-auth", "web-auth"})
class BrowserPlanningOffHttpTest {
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("ROUTIQO_AUTH_RATE_SECRET", () -> UUID.randomUUID().toString());
    }
    @Value("${local.server.port}") int port;
    @Autowired ApplicationContext context;

    HttpResponse<String> send(HttpClient client, String path, String body, String csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/" + path))
                .timeout(java.time.Duration.ofSeconds(10));
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").header("Origin", "http://localhost:3000")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (csrf != null) builder.header("X-XSRF-TOKEN", csrf);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void pathsAreDeniedAndKeepTheDefaultBodyLimit() throws Exception {
        assertThat(context.getBeanNamesForType(BrowserPlanningController.class)).isEmpty();
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = JsonPath.read(send(client, "auth/csrf", null, null).body(), "$.token");
        // Denied by the security chain; anonymous denial uses its 401 entry point.
        assertThat(send(client, "planning", null, null).statusCode()).isEqualTo(401);
        assertThat(send(client, "planning", "{}", csrf).statusCode()).isEqualTo(401);
        assertThat(send(client, "planning/delete", "{\"expectedVersion\":1}", csrf).statusCode()).isEqualTo(401);
        assertThat(send(client, "planning", "x".repeat(20 * 1024 + 1), csrf).statusCode()).isEqualTo(413);
    }
}
