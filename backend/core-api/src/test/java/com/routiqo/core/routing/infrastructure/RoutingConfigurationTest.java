package com.routiqo.core.routing.infrastructure;

import com.routiqo.core.routing.application.PlaceProvider;
import com.routiqo.core.routing.application.RegionLimitedRouteProvider;
import com.routiqo.core.routing.application.RouteProvider;
import com.routiqo.core.routing.domain.RouteOutsideCoverageException;
import com.routiqo.core.routing.domain.RouteRequest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingConfigurationTest {
    private static final String[] CONFIGURATION = {
        "ROUTIQO_VALHALLA_ORIGIN=http://127.0.0.1:18002",
        "ROUTIQO_PHOTON_ORIGIN=http://127.0.0.1:12322",
        "ROUTIQO_ROUTING_REGION_WEST=78",
        "ROUTIQO_ROUTING_REGION_SOUTH=11",
        "ROUTIQO_ROUTING_REGION_EAST=81",
        "ROUTIQO_ROUTING_REGION_NORTH=14"
    };
    private static final String[] PROPERTY_NAMES = Arrays.stream(CONFIGURATION)
            .map(property -> property.substring(0, property.indexOf('=')))
            .toArray(String[]::new);

    private final ApplicationContextRunner active = new ApplicationContextRunner()
            .withUserConfiguration(RoutingConfiguration.class)
            .withPropertyValues("spring.profiles.active=web-auth,routing");

    @Test void startsPairedSelfHostedProvidersWithoutNetworkOrMapboxConfiguration() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            active.withPropertyValues(
                    "ROUTIQO_VALHALLA_ORIGIN=" + origin,
                    "ROUTIQO_PHOTON_ORIGIN=" + origin,
                    "ROUTIQO_ROUTING_REGION_WEST=78",
                    "ROUTIQO_ROUTING_REGION_SOUTH=11",
                    "ROUTIQO_ROUTING_REGION_EAST=81",
                    "ROUTIQO_ROUTING_REGION_NORTH=14")
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(RouteProvider.class)
                                .hasSingleBean(PlaceProvider.class);
                        RouteProvider routes = context.getBean(RouteProvider.class);
                        PlaceProvider places = context.getBean(PlaceProvider.class);
                        assertThat(routes).isInstanceOf(RegionLimitedRouteProvider.class);
                        assertThat(routes.identity()).isEqualTo(RouteProvider.Identity.VALHALLA);
                        assertThat(places).isInstanceOf(PhotonPlaceProvider.class);
                        assertThat(places.identity()).isEqualTo(PlaceProvider.Identity.PHOTON);
                        assertThat(calls).hasValue(0);

                        var outside = new RouteRequest(RouteRequest.Mode.DRIVING,
                                new RouteRequest.Coordinate(80, 13),
                                new RouteRequest.Coordinate(82, 12));
                        assertThatThrownBy(() -> routes.routes(outside))
                                .isInstanceOf(RouteOutsideCoverageException.class)
                                .hasNoCause()
                                .hasMessage("Route is outside the configured coverage region");
                        assertThat(calls).hasValue(0);
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test void requiresTheCompleteProviderAndRegionBundle() {
        for (String missing : PROPERTY_NAMES) {
            String[] partial = Arrays.stream(CONFIGURATION)
                    .filter(property -> !property.startsWith(missing + "="))
                    .toArray(String[]::new);
            active.withPropertyValues(partial).run(context -> {
                assertThat(context).hasFailed();
                assertRedacted(context.getStartupFailure(), missing);
            });
        }
    }

    @Test void rejectsAndRedactsInvalidOriginsNumbersAndBounds() {
        assertInvalid("ROUTIQO_VALHALLA_ORIGIN=https://private.internal/secret?coordinate=80.123",
                "private.internal", "secret", "80.123");
        assertInvalid("ROUTIQO_PHOTON_ORIGIN=https://private-user@photon.internal",
                "private-user", "photon.internal");
        assertInvalid("ROUTIQO_ROUTING_REGION_WEST=80.123private", "80.123private");
        assertInvalid("ROUTIQO_ROUTING_REGION_WEST=NaN", "NaN");
        assertInvalid("ROUTIQO_ROUTING_REGION_EAST=77.999private-coordinate", "77.999private-coordinate");
        assertInvalid("ROUTIQO_ROUTING_REGION_EAST=77", "ROUTIQO_ROUTING_REGION_EAST", "=77");
    }

    @Test void profileExpressionRequiresBothWebAuthAndRouting() {
        for (String profiles : new String[] {"", "web-auth", "routing"}) {
            var context = new ApplicationContextRunner().withUserConfiguration(RoutingConfiguration.class);
            if (!profiles.isEmpty()) context = context.withPropertyValues("spring.profiles.active=" + profiles);
            context.run(result -> assertThat(result).hasNotFailed()
                    .doesNotHaveBean(RouteProvider.class)
                    .doesNotHaveBean(PlaceProvider.class));
        }
    }

    private void assertInvalid(String invalidProperty, String... privateValues) {
        String propertyName = invalidProperty.substring(0, invalidProperty.indexOf('='));
        String[] configured = Arrays.stream(CONFIGURATION)
                .filter(property -> !property.startsWith(propertyName + "="))
                .toArray(String[]::new);
        active.withPropertyValues(configured).withPropertyValues(invalidProperty).run(context -> {
            assertThat(context).hasFailed();
            assertRedacted(context.getStartupFailure(), privateValues);
        });
    }

    private static void assertRedacted(Throwable failure, String... privateValues) {
        assertThat(failure).isNotNull();
        Throwable root = failure;
        StringBuilder messages = new StringBuilder();
        while (root != null) {
            if (root.getMessage() != null) messages.append(root.getMessage()).append('\n');
            if (root.getCause() == null) break;
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(IllegalStateException.class)
                .hasMessage("Routing providers are not configured")
                .hasNoCause();
        assertThat(messages.toString()).doesNotContain(privateValues);
    }
}
