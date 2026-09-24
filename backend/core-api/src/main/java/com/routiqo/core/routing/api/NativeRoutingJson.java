package com.routiqo.core.routing.api;

import com.routiqo.core.routing.domain.PlaceQuery;
import com.routiqo.core.routing.domain.RouteRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exact request parsing at the native provider boundary. */
final class NativeRoutingJson {
    private static final int MAX_BODY_BYTES = 20 * 1024;
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private NativeRoutingJson() {}

    static PlaceQuery place(HttpServletRequest request) {
        JsonNode input = object(request, Set.of("query"));
        JsonNode query = input.get("query");
        if (query == null || !query.isTextual()) throw invalid();
        return new PlaceQuery(query.textValue());
    }

    static RouteRequest route(HttpServletRequest request) {
        JsonNode input = object(request, Set.of("mode", "origin", "destination"));
        JsonNode modeValue = input.get("mode");
        if (modeValue == null || !modeValue.isTextual()) throw invalid();
        RouteRequest.Mode mode = switch (modeValue.textValue()) {
            case "driving" -> RouteRequest.Mode.DRIVING;
            case "walking" -> RouteRequest.Mode.WALKING;
            case "cycling" -> RouteRequest.Mode.CYCLING;
            default -> throw invalid();
        };
        return new RouteRequest(mode, coordinate(input.get("origin")), coordinate(input.get("destination")));
    }

    private static RouteRequest.Coordinate coordinate(JsonNode value) {
        if (value == null || !value.isArray() || value.size() != 2
                || !value.get(0).isNumber() || !value.get(1).isNumber()) throw invalid();
        return new RouteRequest.Coordinate(value.get(0).doubleValue(), value.get(1).doubleValue());
    }

    private static JsonNode object(HttpServletRequest request, Set<String> keys) {
        try {
            byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) throw invalid();
            String raw = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode input = MAPPER.readTree(raw);
            if (input == null || !input.isObject() || !input.propertyNames().equals(keys)) throw invalid();
            return input;
        } catch (Exception malformed) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid native routing request");
    }
}
