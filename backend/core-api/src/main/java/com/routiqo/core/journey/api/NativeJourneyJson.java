package com.routiqo.core.journey.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class NativeJourneyJson {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private NativeJourneyJson() {}

    static JsonNode object(HttpServletRequest request, Set<String> keys) {
        try {
            JsonNode node = MAPPER.readTree(request.getInputStream());
            if (node == null || !node.isObject() || !node.propertyNames().equals(keys)) throw invalid();
            return node;
        } catch (IOException | RuntimeException error) {
            throw invalid();
        }
    }
    static String text(JsonNode object, String name, int maximum) {
        JsonNode node = object.get(name);
        if (node == null || !node.isTextual() || node.textValue().isBlank()
                || node.textValue().length() > maximum) throw invalid();
        return node.textValue();
    }
    static UUID uuid(JsonNode object, String name) {
        String value = text(object, name, 36);
        try {
            UUID result = UUID.fromString(value);
            if (!result.toString().equals(value)) throw invalid();
            return result;
        } catch (IllegalArgumentException error) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid native journey request"); }
}
