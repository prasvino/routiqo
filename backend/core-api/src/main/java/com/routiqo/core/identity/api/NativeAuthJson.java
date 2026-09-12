package com.routiqo.core.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class NativeAuthJson {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private NativeAuthJson() {}

    static JsonNode object(HttpServletRequest request, Set<String> expected) {
        try {
            JsonNode node = MAPPER.readTree(request.getInputStream());
            if (node == null || !node.isObject() || !node.propertyNames().equals(expected)) throw malformed();
            return node;
        } catch (IOException | RuntimeException error) {
            throw malformed();
        }
    }

    static void emptyObject(HttpServletRequest request) { object(request, Set.of()); }

    static String text(JsonNode object, String name, int maxLength) {
        JsonNode value = object.get(name);
        if (value == null || !value.isTextual()) throw malformed();
        String text = value.textValue();
        if (text.isBlank() || text.length() > maxLength) throw malformed();
        return text;
    }

    static UUID uuid(JsonNode object, String name) {
        String text = text(object, name, 36);
        try {
            UUID value = UUID.fromString(text);
            if (!value.toString().equals(text)) throw malformed();
            return value;
        } catch (IllegalArgumentException error) {
            throw malformed();
        }
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("Invalid native authentication request");
    }
}
