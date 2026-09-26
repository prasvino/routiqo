package com.routiqo.core.spot.api;

import com.routiqo.core.spot.application.SpotActivityService;
import com.routiqo.core.spot.domain.SpotActivity;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Strict hand-parsed activity input and output. Spot IDs never pass through Spring's message converters,
 * so framework DEBUG logging cannot record them.
 */
final class NativeSpotJson {
    static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final int MAX_BYTES = 20 * 1024;
    private static final String CANONICAL_ID =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final UUID NIL_ID = new UUID(0, 0);
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private NativeSpotJson() {}

    /** Exactly {@code {"spotIds":[...]}} with 1-20 distinct, strictly ascending, lowercase IDs. */
    static List<UUID> activityRequest(HttpServletRequest request) {
        try {
            byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw invalid();
            String raw = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode node = MAPPER.readTree(raw);
            if (node == null || !node.isObject() || !node.propertyNames().equals(Set.of("spotIds")))
                throw invalid();
            JsonNode ids = node.get("spotIds");
            if (!ids.isArray() || ids.isEmpty() || ids.size() > SpotActivityService.MAX_SPOTS)
                throw invalid();
            var result = new ArrayList<UUID>(ids.size());
            String previous = null;
            for (JsonNode id : ids) {
                if (!id.isTextual()) throw invalid();
                String text = id.textValue();
                if (!text.matches(CANONICAL_ID) || previous != null && previous.compareTo(text) >= 0)
                    throw invalid();
                UUID parsed = UUID.fromString(text);
                if (NIL_ID.equals(parsed)) throw invalid();
                result.add(parsed);
                previous = text;
            }
            return List.copyOf(result);
        } catch (Exception malformed) {
            throw invalid();
        }
    }

    static byte[] activityResponse(SpotActivity activity) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("serverTime", activity.serverTime().toString());
        root.put("catalogVersion", activity.catalogVersion().toString());
        ArrayNode spots = root.putArray("spots");
        for (SpotActivity.Entry entry : activity.spots()) {
            ObjectNode spot = spots.addObject();
            spot.put("id", entry.id().toString());
            spot.put("state", entry.state().key());
            spot.putArray("alertIds");
        }
        root.putArray("alerts");
        byte[] body = MAPPER.writeValueAsBytes(root);
        if (body.length > MAX_RESPONSE_BYTES) throw new ResponseTooLarge();
        return body;
    }

    static final class ResponseTooLarge extends RuntimeException {
        ResponseTooLarge() { super(null, null, false, false); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid native Spot activity request");
    }
}
