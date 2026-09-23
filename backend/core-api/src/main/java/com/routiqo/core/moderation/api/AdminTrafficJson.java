package com.routiqo.core.moderation.api;

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

final class AdminTrafficJson {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private AdminTrafficJson() {}
    record Exchange(UUID challengeId, String idToken) {
        @Override public String toString() { return "Exchange[redacted]"; }
    }
    record Decision(UUID requestId, String reason) {}
    static Exchange exchange(HttpServletRequest request) {
        JsonNode node = parse(request, 17_000, Set.of("challengeId", "idToken"));
        if (!node.get("challengeId").isTextual() || !node.get("idToken").isTextual()) throw invalid();
        String token = node.get("idToken").textValue();
        if (token.isBlank() || token.length() > 16_384) throw invalid();
        return new Exchange(id(node.get("challengeId").textValue()), token);
    }
    static Decision decision(HttpServletRequest request) {
        JsonNode node = parse(request, 512, Set.of("requestId", "reason"));
        if (!node.get("requestId").isTextual() || !node.get("reason").isTextual()) throw invalid();
        return new Decision(id(node.get("requestId").textValue()), node.get("reason").textValue());
    }
    static UUID id(String raw) {
        if (raw == null || !raw.matches(
                "(?!00000000-0000-0000-0000-000000000000$)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw invalid();
        return UUID.fromString(raw);
    }
    private static JsonNode parse(HttpServletRequest request, int limit, Set<String> names) {
        try {
            byte[] body = request.getInputStream().readNBytes(limit + 1);
            if (body.length > limit) throw invalid();
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject() || !node.propertyNames().equals(names)) throw invalid();
            return node;
        } catch (IOException | RuntimeException failure) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid admin request"); }
}
