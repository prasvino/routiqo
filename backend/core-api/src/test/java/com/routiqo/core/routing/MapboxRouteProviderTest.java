package com.routiqo.core.routing;

import com.routiqo.core.routing.domain.RouteOption;
import com.routiqo.core.routing.domain.RouteRequest;
import com.routiqo.core.routing.domain.RouteStep;
import com.routiqo.core.routing.infrastructure.MapboxRouteProvider;
import java.util.concurrent.atomic.AtomicReference;
import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapboxRouteProviderTest {
    private static final String STEP = """
            {"distance":1200,"duration":600,"maneuver":{"instruction":"Continue south","location":[80,13]}}
            """.trim();
    private final RouteRequest request = new RouteRequest(RouteRequest.Mode.DRIVING,
            new RouteRequest.Coordinate(80, 13), new RouteRequest.Coordinate(79, 12));
    @Test void validatesAndNormalizesProviderResults() {
        var captured = new AtomicReference<URI>();
        var provider = new MapboxRouteProvider("synthetic-token", uri -> {
            captured.set(uri);
            return routeJson("[" + STEP + "]");
        });
        var result = provider.routes(request);
        assertEquals(1200, result.getFirst().distanceMetres());
        assertEquals(new RouteStep("Continue south", 1200, 600, request.origin()), result.getFirst().steps().getFirst());
        assertEquals("api.mapbox.com", captured.get().getHost());
        assertTrue(captured.get().getPath().startsWith("/directions/v5/mapbox/driving/"));
        assertTrue(captured.get().getQuery().contains("steps=true"));
        assertTrue(captured.get().getQuery().contains("language=en"));
        assertFalse(request.toString().contains("80.0"));
    }
    @Test void noRouteIsDistinctFromFailure() {
        assertTrue(new MapboxRouteProvider("synthetic-token", uri -> "{\"code\":\"NoRoute\"}").routes(request).isEmpty());
        var error = assertThrows(IllegalStateException.class,
                () -> new MapboxRouteProvider("synthetic-token", uri -> { throw new Exception(uri.toString()); }).routes(request));
        assertFalse(error.toString().contains("synthetic-token"));
        assertNull(error.getCause());
    }
    @Test void invalidEndpointsAndGeometryFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new RouteRequest.Coordinate(181, 0));
        assertThrows(IllegalStateException.class, () -> new MapboxRouteProvider("synthetic-token", uri ->
                routeJson("[" + STEP + "]").replace("[79,12]", "[79,100]")).routes(request));
    }
    @Test void requiresOneLegAndBoundedValidSteps() {
        for (String malformed : java.util.List.of(
                routeJson(null),
                routeJson("[]"),
                routeJson("[" + STEP.replace("Continue south", " Continue south") + "]"),
                routeJson("[" + STEP.replace("1200,\"duration\"", "-1,\"duration\"") + "]"),
                routeJson("[{\"distance\":1,\"duration\":1,\"maneuver\":{\"location\":[80,13]}}]")))
            assertThrows(IllegalStateException.class,
                    () -> new MapboxRouteProvider("synthetic-token", uri -> malformed).routes(request));
        String oversized = "[" + String.join(",", java.util.Collections.nCopies(501, STEP)) + "]";
        assertThrows(IllegalStateException.class,
                () -> new MapboxRouteProvider("synthetic-token", uri -> routeJson(oversized)).routes(request));
        String twoLegs = routeJson("[" + STEP + "]").replace("\"legs\":[{\"steps\":[" + STEP + "]}]",
                "\"legs\":[{\"steps\":[" + STEP + "]},{\"steps\":[" + STEP + "]}]");
        assertThrows(IllegalStateException.class,
                () -> new MapboxRouteProvider("synthetic-token", uri -> twoLegs).routes(request));
    }
    @Test void routeDomainKeepsLegacyConstructionAndRejectsUnsafeInstructions() {
        assertTrue(new RouteOption(1, 1, java.util.List.of(request.origin(), request.destination())).steps().isEmpty());
        assertFalse(new RouteStep("Private road name", 1, 1, request.origin()).toString().contains("Private road name"));
        for (String instruction : java.util.List.of("", " Turn", "Turn\nright", "x".repeat(501)))
            assertThrows(IllegalArgumentException.class, () -> new RouteStep(instruction, 1, 1, request.origin()));
    }
    private static String routeJson(String steps) {
        String legs = steps == null ? "" : ",\"legs\":[{\"steps\":" + steps + "}]";
        return "{\"code\":\"Ok\",\"routes\":[{\"distance\":1200,\"duration\":600,"
                + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[80,13],[79,12]]}" + legs + "}]}";
    }
}
