package com.routiqo.core.routeupdate.api;

import com.routiqo.core.routing.domain.RouteRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict, bounded native route-binding input; no actor or catalog override. */
final class NativeRouteContextJson {
    private static final UUID NIL_ID = new UUID(0, 0);
    private static final int MAX_BYTES = 20 * 1024;
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private NativeRouteContextJson() {}

    static BindingRequest binding(HttpServletRequest request) {
        try {
            byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw invalid();
            String raw = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode node = MAPPER.readTree(raw);
            if (node == null || !node.isObject() || !node.propertyNames().equals(Set.of(
                    "mode", "origin", "destination", "alternativeIndex", "expectedContextId"))) {
                throw invalid();
            }
            JsonNode modeNode = node.get("mode");
            JsonNode alternativeNode = node.get("alternativeIndex");
            JsonNode expectedNode = node.get("expectedContextId");
            if (!modeNode.isTextual() || !alternativeNode.isIntegralNumber()
                    || !alternativeNode.canConvertToInt()
                    || !(expectedNode.isNull() || expectedNode.isTextual())) throw invalid();
            RouteRequest.Mode mode = switch (modeNode.textValue()) {
                case "driving" -> RouteRequest.Mode.DRIVING;
                case "walking" -> RouteRequest.Mode.WALKING;
                case "cycling" -> RouteRequest.Mode.CYCLING;
                default -> throw invalid();
            };
            int alternative = alternativeNode.intValue();
            if (alternative < 0 || alternative > 2) throw invalid();
            Optional<UUID> expected = expectedNode.isNull() ? Optional.empty()
                    : Optional.of(canonicalId(expectedNode.textValue()));
            return new BindingRequest(new RouteRequest(mode, coordinate(node.get("origin")),
                    coordinate(node.get("destination"))), alternative, expected);
        } catch (Exception malformed) {
            throw invalid();
        }
    }

    private static RouteRequest.Coordinate coordinate(JsonNode node) {
        if (node == null || !node.isArray() || node.size() != 2
                || !node.get(0).isNumber() || !node.get(1).isNumber()) throw invalid();
        double longitude = node.get(0).doubleValue(), latitude = node.get(1).doubleValue();
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)) throw invalid();
        return new RouteRequest.Coordinate(longitude, latitude);
    }

    private static UUID canonicalId(String value) {
        if (value == null || !value.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid();
        UUID id = UUID.fromString(value);
        if (NIL_ID.equals(id)) throw invalid();
        return id;
    }

    record BindingRequest(RouteRequest route, int alternativeIndex, Optional<UUID> expectedContextId) {
        @Override public String toString() { return "NativeRouteContextRequest[private]"; }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid native route context request");
    }
}
