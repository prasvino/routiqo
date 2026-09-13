package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.journey.application.JourneyService;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.privacy.application.PresenceConsentService;
import com.routiqo.core.routeupdate.application.RouteBindingService;
import com.routiqo.core.routeupdate.domain.RouteBindingOutcome;
import com.routiqo.core.routing.domain.RouteRequest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com",
    "ROUTIQO_WEB_ORIGIN=http://localhost:3000",
    "ROUTIQO_AUTH_SECURE_COOKIES=false",
    "ROUTIQO_ROUTING_REGION_WEST=-1", "ROUTIQO_ROUTING_REGION_SOUTH=-1",
    "ROUTIQO_ROUTING_REGION_EAST=1", "ROUTIQO_ROUTING_REGION_NORTH=1",
    "ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED=true"
})
@ActiveProfiles({"persistence", "google-auth", "web-auth", "routing"})
class RouteBindingConfiguredTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final HttpServer SERVER;
    private static final Path CATALOG;

    static {
        try {
            DATABASE.start();
            CATALOG = Files.createTempFile("routiqo-binding-catalog-", ".json");
            Files.writeString(CATALOG, """
                {"version":"00000000-0000-4000-8000-000000000201","anchors":[{
                  "id":"00000000-0000-4000-8000-000000000202",
                  "longitude":0,"latitude":0,"categories":["QUEUE"]}]}
                """, StandardCharsets.UTF_8);
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/route", exchange -> {
                CALLS.incrementAndGet();
                byte[] response = valhallaResponse().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            SERVER.start();
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("ROUTIQO_AUTH_RATE_SECRET",
                () -> "configured-route-binding-test-secret-key");
        properties.add("ROUTIQO_VALHALLA_ORIGIN",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        properties.add("ROUTIQO_PHOTON_ORIGIN",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        properties.add("ROUTIQO_LIVE_ANCHOR_CATALOG_PATH", CATALOG::toString);
    }

    @AfterAll
    static void stop() throws Exception {
        SERVER.stop(0);
        Files.deleteIfExists(CATALOG);
    }

    @Autowired ApplicationContext applicationContext;
    @Autowired RouteBindingService bindings;
    @Autowired JourneyService journeys;
    @Autowired PresenceConsentService consents;
    @Autowired JdbcTemplate jdbc;

    @Test
    void productionProfilesComposeOneBinderAndUseConfiguredBoundedValhalla() {
        assertThat(applicationContext.getBeansOfType(RouteBindingService.class)).hasSize(1);
        UUID actor = UUID.randomUUID();
        UUID journey = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)",
                actor, actor.toString());
        journeys.start(actor, journey, Journey.Kind.TRIP);
        consents.submitIntent(actor, journey, 0, true);

        RouteBindingOutcome outcome = bindings.bind(actor, journey,
                new RouteRequest(RouteRequest.Mode.DRIVING,
                        new RouteRequest.Coordinate(-0.02, 0),
                        new RouteRequest.Coordinate(0.02, 0)), 0, java.util.Optional.empty());

        assertThat(outcome.status()).isEqualTo(RouteBindingOutcome.Status.BOUND);
        assertThat(outcome.context().orElseThrow().context().anchorIds())
                .containsExactly(UUID.fromString("00000000-0000-4000-8000-000000000202"));
        assertThat(CALLS).hasValue(1);
    }

    private static String valhallaResponse() {
        String shape = encode(List.of(new double[] {-0.02, 0}, new double[] {0, 0},
                new double[] {0.02, 0}));
        return "{\"trip\":{\"locations\":[{\"type\":\"break\"},{\"type\":\"break\"}],"
                + "\"legs\":[{\"maneuvers\":[{\"instruction\":\"Continue\",\"length\":4.4,"
                + "\"time\":60,\"begin_shape_index\":0,\"end_shape_index\":2}],"
                + "\"summary\":{\"length\":4.4,\"time\":60},\"shape\":\"" + shape + "\"}],"
                + "\"summary\":{\"length\":4.4,\"time\":60},\"status\":0,"
                + "\"units\":\"kilometers\",\"language\":\"en-US\"}}";
    }

    private static String encode(List<double[]> coordinates) {
        StringBuilder encoded = new StringBuilder();
        long previousLatitude = 0;
        long previousLongitude = 0;
        for (double[] coordinate : coordinates) {
            long longitude = Math.round(coordinate[0] * 1_000_000);
            long latitude = Math.round(coordinate[1] * 1_000_000);
            encodeValue(encoded, latitude - previousLatitude);
            encodeValue(encoded, longitude - previousLongitude);
            previousLatitude = latitude;
            previousLongitude = longitude;
        }
        return encoded.toString();
    }

    private static void encodeValue(StringBuilder encoded, long signed) {
        long value = signed < 0 ? ~(signed << 1) : signed << 1;
        while (value >= 0x20) {
            encoded.append((char) ((0x20 | (value & 0x1f)) + 63));
            value >>= 5;
        }
        encoded.append((char) (value + 63));
    }
}
