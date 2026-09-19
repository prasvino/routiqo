package com.routiqo.core.routeupdate.api;

import com.jayway.jsonpath.JsonPath;
import com.routiqo.core.identity.application.GoogleIdentityVerifier;
import com.routiqo.core.routeupdate.application.CatalogSignalService;
import com.routiqo.core.routeupdate.domain.SignalCommandStopResult;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com",
    "ROUTIQO_WEB_ORIGIN=http://localhost:3000",
    "ROUTIQO_AUTH_SECURE_COOKIES=false",
    "ROUTIQO_VALHALLA_ORIGIN=http://127.0.0.1:18002",
    "ROUTIQO_PHOTON_ORIGIN=http://127.0.0.1:12322",
    "ROUTIQO_ROUTING_REGION_WEST=78", "ROUTIQO_ROUTING_REGION_SOUTH=11",
    "ROUTIQO_ROUTING_REGION_EAST=81", "ROUTIQO_ROUTING_REGION_NORTH=14",
    "ROUTIQO_LIVE_SIGNAL_API_ENABLED=true",
    "ROUTIQO_LIVE_CHOICE_API_ENABLED=false"
})
@ActiveProfiles({"persistence", "google-auth", "web-auth", "routing"})
@Import(BrowserSignalStopChoiceOffHttpTest.Dependencies.class)
class BrowserSignalStopChoiceOffHttpTest {
    private static final PostgreSQLContainer DATABASE =
            new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("ROUTIQO_AUTH_RATE_SECRET",
                () -> "stop-choice-off-test-secret-32chars");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean @Primary GoogleIdentityVerifier identity() {
            return (token, nonce) -> {
                if (!token.startsWith(nonce + ":")) throw new SecurityException("Rejected");
                return new GoogleIdentityVerifier.Identity("google",
                        UUID.fromString(token.substring(nonce.length() + 1)).toString());
            };
        }

        @Bean @Primary CatalogSignalService signals() {
            return mock(CatalogSignalService.class);
        }
    }

    @Value("${local.server.port}") int port;
    @Autowired CatalogSignalService signals;
    @Autowired ApplicationContext context;

    @Test void choiceOffStillPermitsSignalStopThroughTheSecurityChain() throws Exception {
        assertThat(context.getBeansOfType(BrowserSignalController.class)).hasSize(1);
        assertThat(context.getBeansOfType(BrowserSignalChoiceController.class)).isEmpty();
        Browser browser = login();
        UUID journey = UUID.randomUUID();
        UUID command = UUID.randomUUID();
        when(signals.stopCommand(browser.account(), journey, command))
                .thenReturn(new SignalCommandStopResult(command, Optional.empty()));

        HttpResponse<String> stopped = send(browser,
                "journeys/" + journey + "/signal-commands/" + command + "/stop", "{}");
        assertThat(stopped.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(stopped.body(), "$.status")).isEqualTo("stopped");
        assertThat(JsonPath.<Object>read(stopped.body(), "$.receipt")).isNull();

        HttpResponse<String> choices = send(browser,
                "journeys/" + journey + "/signal-choices", null);
        assertThat(choices.statusCode()).isIn(401, 403);
        assertThat(choices.body()).isEmpty();
    }

    private Browser login() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = JsonPath.read(send(client, "auth/csrf", null, null, null, null).body(),
                "$.token");
        HttpResponse<String> challenge = send(client, "auth/google/challenge", "{}", csrf,
                "http://localhost:3000", null);
        String id = JsonPath.read(challenge.body(), "$.id");
        String nonce = JsonPath.read(challenge.body(), "$.nonce");
        HttpResponse<String> exchange = send(client, "auth/google/exchange",
                "{\"challengeId\":\"" + id + "\",\"idToken\":\"" + nonce + ":"
                        + UUID.randomUUID() + "\"}", csrf, "http://localhost:3000", null);
        assertThat(exchange.statusCode()).isEqualTo(200);
        return new Browser(client, csrf,
                UUID.fromString(JsonPath.read(exchange.body(), "$.accountId")));
    }

    private HttpResponse<String> send(Browser browser, String path, String body) throws Exception {
        return send(browser.client(), path, body, browser.csrf(), "http://localhost:3000",
                browser.account().toString());
    }

    private HttpResponse<String> send(HttpClient client, String path, String body, String csrf,
            String origin, String account) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/" + path));
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
        if (origin != null) request.header("Origin", origin);
        if (account != null) request.header("X-Routiqo-Account", account);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record Browser(HttpClient client, String csrf, UUID account) {}
}
