package com.routiqo.core.routing;

import com.routiqo.core.routing.domain.RouteRequest;
import com.routiqo.core.routing.infrastructure.MapboxRouteProvider;
import java.util.concurrent.atomic.AtomicReference;
import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapboxRouteProviderTest {
    private final RouteRequest request = new RouteRequest(RouteRequest.Mode.DRIVING,
            new RouteRequest.Coordinate(80, 13), new RouteRequest.Coordinate(79, 12));
    @Test void validatesAndNormalizesProviderResults() {
        var captured = new AtomicReference<URI>();
        var provider = new MapboxRouteProvider("synthetic-token", uri -> {
            captured.set(uri);
            return """
                {"code":"Ok","routes":[{"distance":1200,"duration":600,
                "geometry":{"type":"LineString","coordinates":[[80,13],[79,12]]}}]}
                """;
        });
        var result = provider.routes(request);
        assertEquals(1200, result.getFirst().distanceMetres());
        assertEquals("api.mapbox.com", captured.get().getHost());
        assertTrue(captured.get().getPath().startsWith("/directions/v5/mapbox/driving/"));
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
                "{\"code\":\"Ok\",\"routes\":[{\"distance\":0,\"duration\":0,\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[80,13],[79,100]]}}]}").routes(request));
    }
}
