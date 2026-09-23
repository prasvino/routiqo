package com.routiqo.core.publiclive.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exact V3 purpose parser. No client anchor, value, position, or time authority. */
final class BrowserCommunityTrafficJson {
    static final String PURPOSE = "community-traffic-v3";
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private BrowserCommunityTrafficJson() {}

    static UUID share(HttpServletRequest request) {
        JsonNode node = object(request, Set.of("requestId", "purpose"));
        if (!node.get("purpose").isTextual() || !PURPOSE.equals(node.get("purpose").textValue())
                || !node.get("requestId").isTextual()) throw malformed();
        return id(node.get("requestId").textValue());
    }

    static void stop(HttpServletRequest request) { object(request, Set.of()); }

    static UUID id(String raw) {
        if (raw == null || !raw.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw malformed();
        UUID id = UUID.fromString(raw);
        if (id.equals(new UUID(0, 0))) throw malformed();
        return id;
    }

    private static JsonNode object(HttpServletRequest request, Set<String> expected) {
        try {
            String media = request.getContentType();
            if (media == null || !media.split(";", 2)[0].trim().equalsIgnoreCase("application/json"))
                throw malformed();
            byte[] body = request.getInputStream().readNBytes(20 * 1024 + 1);
            if (body.length > 20 * 1024) throw malformed();
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject() || !node.propertyNames().equals(expected))
                throw malformed();
            return node;
        } catch (IOException | RuntimeException invalid) {
            throw malformed();
        }
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("Invalid community traffic request");
    }
}
