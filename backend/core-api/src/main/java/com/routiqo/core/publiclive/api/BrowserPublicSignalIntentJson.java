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

/** Exact, purpose-bound browser commands. No client-supplied signal or location fields. */
final class BrowserPublicSignalIntentJson {
    static final String PURPOSE = "public-live-moment-v1";
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private BrowserPublicSignalIntentJson() {}

    static UUID share(HttpServletRequest request) {
        JsonNode node = object(request, Set.of("requestId", "purpose"));
        JsonNode purpose = node.get("purpose");
        JsonNode requestId = node.get("requestId");
        if (!purpose.isTextual() || !PURPOSE.equals(purpose.textValue())
                || !requestId.isTextual()) throw malformed();
        return id(requestId.textValue());
    }

    static void stop(HttpServletRequest request) {
        object(request, Set.of());
    }

    static UUID id(String value) {
        if (value == null || !value.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw malformed();
        UUID parsed = UUID.fromString(value);
        if (parsed.equals(new UUID(0, 0))) throw malformed();
        return parsed;
    }

    private static JsonNode object(HttpServletRequest request, Set<String> properties) {
        try {
            String mediaType = request.getContentType();
            if (mediaType == null || !mediaType.split(";", 2)[0].trim()
                    .equalsIgnoreCase("application/json")) throw malformed();
            byte[] body = request.getInputStream().readNBytes(20 * 1024 + 1);
            if (body.length > 20 * 1024) throw malformed();
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject() || !node.propertyNames().equals(properties))
                throw malformed();
            return node;
        } catch (IOException | RuntimeException invalid) {
            throw malformed();
        }
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("Invalid public intent request");
    }
}
