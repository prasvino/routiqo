package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.routeupdate.application.RouteAnchorResolver;
import com.routiqo.core.routeupdate.application.RouteBindingService;
import com.routiqo.core.routeupdate.application.CatalogSignalService;
import com.routiqo.core.routeupdate.domain.ResolvedRouteAnchors;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import com.routiqo.core.routing.application.RegionLimitedRouteProvider;
import com.routiqo.core.routing.application.RouteProvider;
import com.routiqo.core.routing.domain.RouteOutsideCoverageException;
import com.routiqo.core.routing.domain.RouteRequest;
import com.routiqo.core.routing.infrastructure.RoutingConfiguration;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteAnchorResolutionConfigurationTest {
    private static final String VERSION = "00000000-0000-4000-8000-000000000001";
    private static final String ANCHOR = "00000000-0000-4000-8000-000000000002";
    @TempDir Path directory;

    @Test void remainsAbsentByDefaultAndRequiresBothProfiles() throws Exception {
        Path catalog = catalog(0, 0);
        for (String profiles : List.of("", "web-auth", "routing", "web-auth,routing")) {
            var runner = new ApplicationContextRunner()
                    .withUserConfiguration(RouteAnchorResolutionConfiguration.class, TestDependencies.class)
                    .withPropertyValues("ROUTIQO_LIVE_ANCHOR_CATALOG_PATH=" + catalog);
            if (!profiles.isEmpty()) runner = runner.withPropertyValues("spring.profiles.active=" + profiles);
            runner.run(context -> assertThat(context).hasNotFailed()
                    .doesNotHaveBean(RouteAnchorResolver.class)
                    .doesNotHaveBean(RouteBindingService.class)
                    .doesNotHaveBean(CatalogSignalService.class)
                    .doesNotHaveBean(RouteAnchorCatalog.class));
        }

        for (String profiles : List.of("", "web-auth", "routing")) {
            var runner = new ApplicationContextRunner()
                    .withUserConfiguration(RouteAnchorResolutionConfiguration.class, TestDependencies.class)
                    .withPropertyValues("ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED=true",
                            "ROUTIQO_LIVE_ANCHOR_CATALOG_PATH=" + catalog);
            if (!profiles.isEmpty()) runner = runner.withPropertyValues("spring.profiles.active=" + profiles);
            runner.run(context -> assertThat(context).hasNotFailed()
                    .doesNotHaveBean(RouteAnchorResolver.class)
                    .doesNotHaveBean(RouteBindingService.class)
                    .doesNotHaveBean(CatalogSignalService.class));
        }
    }

    @Test void failsClosedForMissingInvalidOrOutOfRegionCatalogsWithRedactedDiagnostics()
            throws Exception {
        assertMisconfigured(new String[0], "ROUTIQO_LIVE_ANCHOR_CATALOG_PATH");
        Path invalid = directory.resolve("private-catalog-location.json");
        Files.writeString(invalid, "{\"private\":\"secret-anchor-value\"}", StandardCharsets.UTF_8);
        assertMisconfigured(new String[] {"ROUTIQO_LIVE_ANCHOR_CATALOG_PATH=" + invalid},
                invalid.toString(), "secret-anchor-value");
        Path outside = catalog(3, 0);
        assertMisconfigured(new String[] {"ROUTIQO_LIVE_ANCHOR_CATALOG_PATH=" + outside},
                outside.toString(), "3.0", ANCHOR);
    }

    @Test void composesTheConfiguredRegionGuardedValhallaAndBoundedLoopbackTransport()
            throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/route", exchange -> {
            calls.incrementAndGet();
            byte[] request = exchange.getRequestBody().readAllBytes();
            assertThat(request.length).isLessThanOrEqualTo(20 * 1024);
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            byte[] response = valhallaResponse(List.of(
                    new double[] {-0.02, 0}, new double[] {0, 0}, new double[] {0.02, 0}))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            new ApplicationContextRunner()
                    .withUserConfiguration(RoutingConfiguration.class,
                            RouteAnchorResolutionConfiguration.class)
                    .withPropertyValues(
                            "spring.profiles.active=web-auth,routing",
                            "ROUTIQO_VALHALLA_ORIGIN=" + origin,
                            "ROUTIQO_PHOTON_ORIGIN=" + origin,
                            "ROUTIQO_ROUTING_REGION_WEST=-2",
                            "ROUTIQO_ROUTING_REGION_SOUTH=-2",
                            "ROUTIQO_ROUTING_REGION_EAST=2",
                            "ROUTIQO_ROUTING_REGION_NORTH=2",
                            "ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED=true",
                            "ROUTIQO_LIVE_ANCHOR_CATALOG_PATH=" + catalog(0, 0))
                    .run(context -> {
                        assertThat(context).hasNotFailed()
                                .hasSingleBean(RouteAnchorResolver.class)
                                .hasSingleBean(RouteAnchorCatalog.class)
                                .hasSingleBean(RouteProvider.class);
                        assertThat(context.getBean(RouteProvider.class))
                                .isInstanceOf(RegionLimitedRouteProvider.class);
                        assertThat(context.getBean(RouteProvider.class).identity())
                                .isEqualTo(RouteProvider.Identity.VALHALLA);
                        assertThat(calls).hasValue(0);

                        var request = new RouteRequest(RouteRequest.Mode.DRIVING,
                                coordinate(-0.02, 0), coordinate(0.02, 0));
                        ResolvedRouteAnchors result = context.getBean(RouteAnchorResolver.class)
                                .resolve(request, 0);
                        assertThat(result.status()).isEqualTo(ResolvedRouteAnchors.Status.ROUTE);
                        assertThat(result.anchors()).containsOnlyKeys(java.util.UUID.fromString(ANCHOR));
                        assertThat(calls).hasValue(1);

                        var outside = new RouteRequest(RouteRequest.Mode.DRIVING,
                                coordinate(-3, 0), coordinate(0.02, 0));
                        assertThatThrownBy(() -> context.getBean(RouteAnchorResolver.class)
                                .resolve(outside, 0))
                                .isExactlyInstanceOf(RouteOutsideCoverageException.class)
                                .hasNoCause();
                        assertThat(calls).hasValue(1);
                    });
        } finally {
            server.stop(0);
        }
    }

    private void assertMisconfigured(String[] properties, String... privateValues) {
        new ApplicationContextRunner()
                .withUserConfiguration(RouteAnchorResolutionConfiguration.class, TestDependencies.class)
                .withPropertyValues("spring.profiles.active=web-auth,routing",
                        "ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED=true")
                .withPropertyValues(properties)
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();
                    StringBuilder messages = new StringBuilder();
                    while (failure != null) {
                        if (failure.getMessage() != null) messages.append(failure.getMessage()).append('\n');
                        if (failure.getCause() == null) break;
                        failure = failure.getCause();
                    }
                    assertThat(failure).isExactlyInstanceOf(IllegalStateException.class)
                            .hasMessage("Route anchor resolver is not configured").hasNoCause();
                    assertThat(messages).doesNotContain(privateValues);
                });
    }

    private Path catalog(double longitude, double latitude) throws Exception {
        Path path = directory.resolve("catalog-" + Double.toString(longitude).replace('.', '_') + ".json");
        Files.writeString(path, "{\"version\":\"" + VERSION + "\",\"anchors\":[{"
                + "\"id\":\"" + ANCHOR + "\",\"longitude\":" + longitude
                + ",\"latitude\":" + latitude + ",\"categories\":[\"QUEUE\"]}]}",
                StandardCharsets.UTF_8);
        return path;
    }

    private static String valhallaResponse(List<double[]> coordinates) {
        String shape = encode(coordinates);
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

    private static RouteRequest.Coordinate coordinate(double longitude, double latitude) {
        return new RouteRequest.Coordinate(longitude, latitude);
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class TestDependencies {
        @org.springframework.context.annotation.Bean RouteProvider provider() {
            return new RouteProvider() {
                @Override public Identity identity() { return Identity.VALHALLA; }
                @Override public java.util.List<com.routiqo.core.routing.domain.RouteOption> routes(
                        RouteRequest request) { return List.of(); }
            };
        }

        @org.springframework.context.annotation.Bean com.routiqo.core.routing.domain.RoutingRegion region() {
            return new com.routiqo.core.routing.domain.RoutingRegion(-2, -2, 2, 2);
        }
    }
}
