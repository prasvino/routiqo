package com.routiqo.core.routing;

import com.routiqo.core.routing.application.RouteProvider;
import com.routiqo.core.routing.domain.RouteRequest;
import com.routiqo.core.routing.infrastructure.BoundedRoutingTransport;
import com.routiqo.core.routing.infrastructure.RoutingPostTransport;
import com.routiqo.core.routing.infrastructure.ValhallaRouteProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValhallaRouteProviderTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final List<double[]> POINTS = List.of(
            new double[] {80.0, 13.0}, new double[] {79.5, 12.5}, new double[] {79.0, 12.0});
    private static final String SHAPE = encode(POINTS);
    private static final String MANEUVERS = "["
            + maneuver("Leave Chennai", 0.5, 40, 0, 1) + ","
            + maneuver("Arrive at destination", 0.7, 50, 1, 2) + "]";
    private static final RouteRequest DRIVING = new RouteRequest(RouteRequest.Mode.DRIVING,
            new RouteRequest.Coordinate(80, 13), new RouteRequest.Coordinate(79, 12));

    @Test void postsTheFixedNativeJsonAndNormalizesPrimaryAndAlternateTrips() throws Exception {
        for (var mode : RouteRequest.Mode.values()) {
            var uri = new AtomicReference<URI>();
            var json = new AtomicReference<String>();
            String expectedCosting = switch (mode) {
                case DRIVING -> "auto";
                case WALKING -> "pedestrian";
                case CYCLING -> "bicycle";
            };
            var provider = new ValhallaRouteProvider(URI.create("https://valhalla.internal:8002/"),
                    (requested, body) -> {
                        uri.set(requested);
                        json.set(body);
                        return new RoutingPostTransport.Response(200,
                                response(trip(1.2, 90, SHAPE, MANEUVERS), 2));
                    });
            var request = new RouteRequest(mode, DRIVING.origin(), DRIVING.destination());

            assertThat(provider.identity()).isEqualTo(RouteProvider.Identity.VALHALLA);
            var routes = provider.routes(request);

            assertThat(uri.get()).isEqualTo(URI.create("https://valhalla.internal:8002/route"));
            assertThat(uri.get().getQuery()).isNull();
            JsonNode sent = MAPPER.readTree(json.get());
            assertThat(sent.path("costing").textValue()).isEqualTo(expectedCosting);
            assertThat(sent.path("units").textValue()).isEqualTo("kilometers");
            assertThat(sent.path("language").textValue()).isEqualTo("en-US");
            assertThat(sent.path("directions_type").textValue()).isEqualTo("instructions");
            assertThat(sent.path("format").textValue()).isEqualTo("json");
            assertThat(sent.path("alternates").intValue()).isEqualTo(2);
            assertThat(sent.has("shape_format")).isFalse();
            assertThat(sent.path("locations")).hasSize(2);
            assertThat(sent.path("locations").get(0).path("type").textValue()).isEqualTo("break");
            assertThat(sent.path("locations").get(0).path("lat").doubleValue()).isEqualTo(13);
            assertThat(sent.path("locations").get(0).path("lon").doubleValue()).isEqualTo(80);

            assertThat(routes).hasSize(3);
            assertThat(routes.getFirst().distanceMetres()).isEqualTo(1200);
            assertThat(routes.getFirst().durationSeconds()).isEqualTo(90);
            assertThat(routes.getFirst().geometry()).extracting(RouteRequest.Coordinate::longitude)
                    .containsExactly(80.0, 79.5, 79.0);
            assertThat(routes.getFirst().geometry()).extracting(RouteRequest.Coordinate::latitude)
                    .containsExactly(13.0, 12.5, 12.0);
            assertThat(routes.getFirst().steps()).hasSize(2);
            assertThat(routes.getFirst().steps().getFirst().distanceMetres()).isEqualTo(500);
            assertThat(routes.getFirst().steps().getFirst().location()).isEqualTo(routes.getFirst().geometry().get(0));
            assertThat(routes.getFirst().steps().get(1).location()).isEqualTo(routes.getFirst().geometry().get(1));
        }
    }

    @Test void constructionDoesNotCallTransportAndRejectsUnsafeOrigins() {
        var calls = new AtomicInteger();
        new ValhallaRouteProvider(URI.create("http://127.0.0.1:8002"), (uri, json) -> {
            calls.incrementAndGet();
            return new RoutingPostTransport.Response(200, response(trip(1, 1, SHAPE, MANEUVERS), 0));
        });
        assertThat(calls).hasValue(0);

        for (String origin : List.of("relative", "ftp://valhalla.internal", "https://user@valhalla.internal",
                "https://valhalla.internal/route", "https://valhalla.internal?x=1",
                "https://valhalla.internal#fragment", "https://valhalla.internal:0",
                "https://valhalla.internal:65536")) {
            assertThatThrownBy(() -> new ValhallaRouteProvider(URI.create(origin), (uri, json) -> {
                calls.incrementAndGet();
                throw new AssertionError("must not run");
            })).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Routing provider is not configured")
                    .hasNoCause();
        }
        String oversized = "https://" + "a".repeat(2048) + ".internal";
        assertThatThrownBy(() -> new ValhallaRouteProvider(URI.create(oversized), (uri, json) -> null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
    }

    @Test void onlyIntegralError442UnderHttp400IsNoRoute() {
        assertThat(provider(400, "{\"error_code\":442,\"status_code\":400,\"status\":\"Bad Request\"}")
                .routes(DRIVING)).isEmpty();

        for (String body : List.of("{}", "{\"error_code\":441}", "{\"error_code\":442.0}",
                "{\"error_code\":\"442\"}", "{\"error_code\":442}{\"error_code\":442}"))
            assertUnavailable(400, body);
        assertUnavailable(200, "{\"error_code\":442}");
        assertUnavailable(201, response(trip(1, 1, SHAPE, MANEUVERS), 0));
        assertUnavailable(500, "{\"error_code\":442}");
    }

    @Test void enforcesTheNativeTripAndAlternateWireShape() {
        String validTrip = trip(1, 1, SHAPE, MANEUVERS);
        String directAlternate = "{\"trip\":" + validTrip + ",\"alternates\":[" + validTrip + "]}";
        String wrappedAlternateWithoutTrip = "{\"trip\":" + validTrip + ",\"alternates\":[{}]}";
        for (String response : List.of(
                "{}", "{\"trip\":[]}", directAlternate, wrappedAlternateWithoutTrip,
                response(validTrip, 3),
                response(validTrip.replace("\"status\":0", "\"status\":1"), 0),
                response(validTrip.replace("\"status\":0", "\"status\":0.0"), 0),
                response(validTrip.replace("\"kilometers\"", "\"miles\""), 0),
                response(validTrip.replace("\"en-US\"", "\"en-GB\""), 0),
                response(validTrip.replace("[{\"type\":\"break\"},{\"type\":\"break\"}]",
                        "[{\"type\":\"break\"}]"), 0),
                response(validTrip.replace("\"legs\":[", "\"legs\":[{},"), 0),
                response(validTrip.replace("\"summary\":{\"length\":1.0,\"time\":1.0},", ""), 0),
                response(validTrip.replace("\"maneuvers\":" + MANEUVERS, "\"maneuvers\":[]"), 0)))
            assertUnavailable(200, response);
    }

    @Test void rejectsDuplicateTrailingOversizedAndMalformedUnicodeJson() {
        String valid = response(trip(1, 1, SHAPE, MANEUVERS), 0);
        assertUnavailable(200, valid + "{}");
        assertUnavailable(200, valid.replaceFirst("\\{", "{\"trip\":{},"));
        assertUnavailable(200, valid.replace("\"status\":0", "\"status\":0,\"status\":0"));
        assertUnavailable(200, valid.replace("\"instruction\":\"Leave Chennai\"",
                "\"instruction\":\"Leave Chennai\",\"instruction\":\"Other\""));
        assertUnavailable(200, "\"" + "é".repeat(524_289) + "\"");
        assertUnavailable(200, valid.replace("Leave Chennai", "\\ud800"));
    }

    @Test void rejectsInvalidOrUnboundedPolyline6Shapes() {
        var tooMany = new ArrayList<double[]>();
        for (int i = 0; i <= 10_000; i++) tooMany.add(new double[] {80, 13});
        for (String shape : List.of("", "?", "_", "!???", "______?", encode(List.of(new double[] {80, 13})),
                encode(List.of(new double[] {0, 0}, new double[] {0, 91})), encode(tooMany)))
            assertUnavailable(200, response(trip(1, 1, shape, MANEUVERS), 0));
    }

    @Test void decodesNegativeInitialCoordinatesAndAValidSixGroupDatelineDelta() {
        List<double[]> points = List.of(
                new double[] {-179.999999, -89.123456},
                new double[] {179.999999, -89.123455});
        String maneuvers = "[" + maneuver("கோட்டை கடக்கவும் 🧭", 1, 1, 0, 1) + "]";

        var route = provider(200, response(trip(1, 1, encode(points), maneuvers), 0))
                .routes(DRIVING).getFirst();

        assertThat(route.geometry()).extracting(RouteRequest.Coordinate::longitude)
                .containsExactly(-179.999999, 179.999999);
        assertThat(route.geometry()).extracting(RouteRequest.Coordinate::latitude)
                .containsExactly(-89.123456, -89.123455);
        assertThat(route.steps().getFirst().instruction()).isEqualTo("கோட்டை கடக்கவும் 🧭");
    }

    @Test void integratesWithTheBoundedPostTransportForSuccessAndNoPath() throws Exception {
        var calls = new AtomicInteger();
        var handlerFailure = new AtomicReference<Throwable>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/route", exchange -> {
            try {
                assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                assertThat(exchange.getRequestURI().getRawQuery()).isNull();
                assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isEqualTo("application/json");
                JsonNode request = MAPPER.readTree(exchange.getRequestBody());
                assertThat(request.path("locations")).hasSize(2);
                assertThat(request.path("costing").textValue()).isEqualTo("auto");
                int call = calls.getAndIncrement();
                String body = call == 0 ? response(trip(1.2, 90, SHAPE, MANEUVERS), 0)
                        : "{\"error_code\":442,\"status_code\":400,\"status\":\"Bad Request\"}";
                byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(call == 0 ? 200 : 400, encoded.length);
                try (var output = exchange.getResponseBody()) { output.write(encoded); }
            } catch (Throwable failure) {
                handlerFailure.set(failure);
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        });
        server.start();
        try {
            var provider = new ValhallaRouteProvider(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    new BoundedRoutingTransport());
            assertThat(provider.routes(DRIVING)).hasSize(1);
            assertThat(provider.routes(DRIVING)).isEmpty();
            assertThat(calls).hasValue(2);
            assertThat(handlerFailure.get()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test void rejectsMalformedUnboundedOrUnorderedManeuvers() {
        String valid = maneuver("Leave Chennai", 0.5, 40, 0, 1);
        String tooMany = "[" + String.join(",", Collections.nCopies(501, valid)) + "]";
        List<String> malformed = List.of(
                "[]", tooMany,
                "[" + valid.replace("Leave Chennai", "") + "]",
                "[" + valid.replace("Leave Chennai", " Leave Chennai") + "]",
                "[" + valid.replace("Leave Chennai", "Leave Chennai ") + "]",
                "[" + valid.replace("Leave Chennai", "Leave\\nChennai") + "]",
                "[" + valid.replace("Leave Chennai", "\\ud800") + "]",
                "[" + valid.replace("Leave Chennai", "x".repeat(501)) + "]",
                "[" + valid.replace("\"length\":0.5", "\"length\":-1") + "]",
                "[" + valid.replace("\"time\":40.0", "\"time\":\"40\"") + "]",
                "[" + valid.replace("\"begin_shape_index\":0", "\"begin_shape_index\":0.0") + "]",
                "[" + valid.replace("\"end_shape_index\":1", "\"end_shape_index\":3") + "]",
                "[" + valid.replace("\"begin_shape_index\":0", "\"begin_shape_index\":2")
                        .replace("\"end_shape_index\":1", "\"end_shape_index\":1") + "]",
                "[" + maneuver("First", 0.5, 40, 1, 2) + "," + maneuver("Second", 0.5, 40, 0, 1) + "]");
        for (String maneuvers : malformed)
            assertUnavailable(200, response(trip(1, 1, SHAPE, maneuvers), 0));
    }

    @Test void rejectsInvalidTotalsAndRedactsFailuresWhilePreservingInterruptStatus() {
        String valid = trip(1, 1, SHAPE, MANEUVERS);
        for (String malformed : List.of(
                valid.replace("\"length\":1.0", "\"length\":-1"),
                valid.replace("\"time\":1.0", "\"time\":\"1\""),
                valid.replace("\"length\":1.0", "\"length\":1e999")))
            assertUnavailable(200, response(malformed, 0));

        var failing = new ValhallaRouteProvider(URI.create("https://private.internal"), (uri, json) -> {
            throw new Exception("private failure " + uri + " " + json);
        });
        assertThatThrownBy(() -> failing.routes(DRIVING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Routing is temporarily unavailable")
                .hasNoCause()
                .hasToString("java.lang.IllegalStateException: Routing is temporarily unavailable");

        var interrupted = new ValhallaRouteProvider(URI.create("https://private.internal"), (uri, json) -> {
            throw new InterruptedException("private interruption " + uri);
        });
        try {
            assertThatThrownBy(() -> interrupted.routes(DRIVING))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Routing request interrupted")
                    .hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static ValhallaRouteProvider provider(int status, String body) {
        return new ValhallaRouteProvider(URI.create("https://valhalla.internal"),
                (uri, json) -> new RoutingPostTransport.Response(status, body));
    }

    private static void assertUnavailable(int status, String body) {
        assertThatThrownBy(() -> provider(status, body).routes(DRIVING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Routing is temporarily unavailable")
                .hasNoCause();
    }

    private static String response(String primary, int alternateCount) {
        StringBuilder response = new StringBuilder("{\"trip\":").append(primary);
        if (alternateCount > 0) {
            response.append(",\"alternates\":[");
            for (int i = 0; i < alternateCount; i++) {
                if (i > 0) response.append(',');
                response.append("{\"trip\":").append(primary).append('}');
            }
            response.append(']');
        }
        return response.append('}').toString();
    }

    private static String trip(double length, double time, String shape, String maneuvers) {
        return "{\"locations\":[{\"type\":\"break\"},{\"type\":\"break\"}],"
                + "\"legs\":[{\"maneuvers\":" + maneuvers + ","
                + "\"summary\":{\"length\":" + length + ",\"time\":" + time + "},"
                + "\"shape\":\"" + shape + "\"}],"
                + "\"summary\":{\"length\":" + length + ",\"time\":" + time + "},"
                + "\"status_message\":\"Found route between points\",\"status\":0,"
                + "\"units\":\"kilometers\",\"language\":\"en-US\"}";
    }

    private static String maneuver(String instruction, double length, double time, int begin, int end) {
        return "{\"type\":1,\"instruction\":\"" + instruction + "\",\"length\":" + length
                + ",\"time\":" + time + ",\"begin_shape_index\":" + begin
                + ",\"end_shape_index\":" + end + "}";
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
