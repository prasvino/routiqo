package com.routiqo.core.routeupdate.api;

import com.routiqo.core.routing.domain.RouteRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class BrowserRouteContextJson {
    private static final UUID NIL_ID = new UUID(0, 0);
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private BrowserRouteContextJson() {}

    static BindingRequest binding(HttpServletRequest request) {
        try {
            JsonNode node = MAPPER.readTree(request.getInputStream());
            if (node == null || !node.isObject() || !node.propertyNames().equals(Set.of(
                    "mode", "origin", "destination", "alternativeIndex", "expectedContextId"))) {
                throw malformed();
            }
            JsonNode modeNode = node.get("mode");
            JsonNode alternativeNode = node.get("alternativeIndex");
            JsonNode expectedNode = node.get("expectedContextId");
            if (modeNode == null || !modeNode.isTextual() || alternativeNode == null
                    || !alternativeNode.isIntegralNumber() || !alternativeNode.canConvertToInt()
                    || expectedNode == null || !(expectedNode.isNull() || expectedNode.isTextual())) {
                throw malformed();
            }
            RouteRequest.Mode mode = switch (modeNode.textValue()) {
                case "driving" -> RouteRequest.Mode.DRIVING;
                case "walking" -> RouteRequest.Mode.WALKING;
                case "cycling" -> RouteRequest.Mode.CYCLING;
                default -> throw malformed();
            };
            int alternative = alternativeNode.intValue();
            if (alternative < 0 || alternative > 2) throw malformed();
            Optional<UUID> expected = expectedNode.isNull()
                    ? Optional.empty() : Optional.of(canonicalId(expectedNode.textValue()));
            return new BindingRequest(new RouteRequest(mode, coordinate(node.get("origin")),
                    coordinate(node.get("destination"))), alternative, expected);
        } catch (IOException | RuntimeException invalid) {
            throw malformed();
        }
    }

    private static RouteRequest.Coordinate coordinate(JsonNode node) {
        if (node == null || !node.isArray() || node.size() != 2
                || !node.get(0).isNumber() || !node.get(1).isNumber()) throw malformed();
        double longitude = node.get(0).doubleValue();
        double latitude = node.get(1).doubleValue();
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)) throw malformed();
        try {
            return new RouteRequest.Coordinate(longitude, latitude);
        } catch (IllegalArgumentException invalid) {
            throw malformed();
        }
    }

    private static UUID canonicalId(String value) {
        if (value == null || !value.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw malformed();
        }
        UUID id = UUID.fromString(value);
        if (NIL_ID.equals(id)) throw malformed();
        return id;
    }

    record BindingRequest(RouteRequest route, int alternativeIndex,
            Optional<UUID> expectedContextId) {
        @Override public String toString() { return "BrowserRouteContextRequest[private]"; }
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("Invalid route context request");
    }
}
