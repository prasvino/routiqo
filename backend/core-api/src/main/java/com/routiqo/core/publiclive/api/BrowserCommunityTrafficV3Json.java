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

/** Small exact report command; no anchor, source, coordinate or contributor fields. */
final class BrowserCommunityTrafficV3Json {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private BrowserCommunityTrafficV3Json() {}

    static Report parse(HttpServletRequest request) {
        try {
            String mediaType = request.getContentType();
            if (mediaType == null || !mediaType.split(";", 2)[0].trim()
                    .equalsIgnoreCase("application/json")) throw invalid();
            byte[] body = request.getInputStream().readNBytes(513);
            if (body.length > 512) throw invalid();
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject() || !node.propertyNames()
                    .equals(Set.of("reason", "clientRequestId"))) throw invalid();
            JsonNode reason = node.get("reason"), requestId = node.get("clientRequestId");
            if (!reason.isTextual() || !requestId.isTextual()) throw invalid();
            return new Report(reason.textValue(), id(requestId.textValue()));
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    static UUID id(String raw) {
        if (raw == null || !raw.matches(
                "(?!00000000-0000-0000-0000-000000000000$)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw invalid();
        return UUID.fromString(raw);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid community traffic request");
    }

    record Report(String reason, UUID requestId) {}
}
