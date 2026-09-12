package com.routiqo.core.routing.infrastructure;

import com.routiqo.core.routing.application.PlaceProvider;
import com.routiqo.core.routing.domain.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Temporary results only. Never include query text or credentials in failures. */
public final class MapboxPlaceProvider implements PlaceProvider {
    private final String token;
    private final MapboxRouteProvider.Transport transport;
    private final JsonMapper mapper = JsonMapper.builder().build();
    public MapboxPlaceProvider(String token, MapboxRouteProvider.Transport transport) {
        if (token == null || token.isBlank() || token.length() > 2048 || transport == null)
            throw new IllegalArgumentException("Place provider is not configured");
        this.token = token; this.transport = transport;
    }
    @Override public Identity identity() { return Identity.MAPBOX; }
    @Override public PlaceResults search(PlaceQuery query) {
        if (query == null) throw new IllegalArgumentException("Place query is required");
        try {
            var uri = URI.create("https://api.mapbox.com/search/geocode/v6/forward?q="
                    + URLEncoder.encode(query.text(), StandardCharsets.UTF_8)
                    + "&autocomplete=false&permanent=false&limit=5&access_token="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8));
            String raw = transport.get(uri);
            if (raw == null || raw.length() > 262144) throw new IllegalArgumentException();
            JsonNode root = mapper.readTree(raw), features = root.path("features");
            if (!"FeatureCollection".equals(root.path("type").asString())
                    || !features.isArray() || features.size() > 5 || !root.path("attribution").isString())
                throw new IllegalArgumentException();
            var places = new ArrayList<PlaceMatch>();
            for (JsonNode feature : features) {
                JsonNode geometry = feature.path("geometry"), point = geometry.path("coordinates"), properties = feature.path("properties");
                if (!"Feature".equals(feature.path("type").asString())
                        || !"Point".equals(geometry.path("type").asString())
                        || !point.isArray() || point.size() != 2 || !point.get(0).isNumber() || !point.get(1).isNumber()
                        || !properties.path("mapbox_id").isString() || !properties.path("full_address").isString())
                    throw new IllegalArgumentException();
                places.add(new PlaceMatch(properties.path("mapbox_id").asString(), properties.path("full_address").asString(),
                        new RouteRequest.Coordinate(point.get(0).asDouble(), point.get(1).asDouble())));
            }
            return new PlaceResults(places, root.path("attribution").asString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Place search interrupted");
        } catch (Exception failure) {
            throw new IllegalStateException("Place search is temporarily unavailable");
        }
    }
}
