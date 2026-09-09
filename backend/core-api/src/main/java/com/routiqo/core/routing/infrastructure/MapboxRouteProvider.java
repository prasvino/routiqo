package com.routiqo.core.routing.infrastructure;

import com.routiqo.core.routing.application.RouteProvider;
import com.routiqo.core.routing.domain.RouteOption;
import com.routiqo.core.routing.domain.RouteRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed provider host. Exceptions deliberately omit request URLs, credentials and response bodies. */
public final class MapboxRouteProvider implements RouteProvider {
    @FunctionalInterface public interface Transport { String get(URI uri) throws Exception; }
    private final String token;
    private final Transport transport;
    private final JsonMapper mapper = JsonMapper.builder().build();
    public MapboxRouteProvider(String token, Transport transport) {
        if (token == null || token.isBlank() || token.length() > 2048 || transport == null)
            throw new IllegalArgumentException("Routing provider is not configured");
        this.token = token; this.transport = transport;
    }
    @Override public List<RouteOption> routes(RouteRequest request) {
        if (request == null) throw new IllegalArgumentException("Route request is required");
        try {
            String coordinates = request.origin().longitude() + "," + request.origin().latitude()
                    + ";" + request.destination().longitude() + "," + request.destination().latitude();
            URI uri = URI.create("https://api.mapbox.com/directions/v5/mapbox/"
                    + request.mode().name().toLowerCase(Locale.ROOT) + "/" + coordinates
                    + "?geometries=geojson&overview=full&alternatives=true&steps=false&access_token="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8));
            String raw = transport.get(uri);
            if (raw == null || raw.length() > 1024 * 1024) throw new IllegalArgumentException();
            JsonNode root = mapper.readTree(raw);
            if ("NoRoute".equals(root.path("code").asString())) return List.of();
            JsonNode routes = root.path("routes");
            if (!"Ok".equals(root.path("code").asString()) || !routes.isArray()
                    || routes.size() < 1 || routes.size() > 3) throw new IllegalArgumentException();
            var results = new ArrayList<RouteOption>();
            for (JsonNode route : routes) {
                JsonNode geometry = route.path("geometry"), points = geometry.path("coordinates");
                if (!route.path("distance").isNumber() || !route.path("duration").isNumber()
                        || !"LineString".equals(geometry.path("type").asString())
                        || !points.isArray() || points.size() < 2 || points.size() > 10000)
                    throw new IllegalArgumentException();
                var coordinatesResult = new ArrayList<RouteRequest.Coordinate>();
                for (JsonNode point : points) {
                    if (!point.isArray() || point.size() != 2 || !point.get(0).isNumber() || !point.get(1).isNumber())
                        throw new IllegalArgumentException();
                    coordinatesResult.add(new RouteRequest.Coordinate(point.get(0).asDouble(), point.get(1).asDouble()));
                }
                results.add(new RouteOption(route.path("distance").asDouble(), route.path("duration").asDouble(), coordinatesResult));
            }
            return List.copyOf(results);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Routing request interrupted");
        } catch (Exception failure) {
            throw new IllegalStateException("Routing is temporarily unavailable");
        }
    }
}
