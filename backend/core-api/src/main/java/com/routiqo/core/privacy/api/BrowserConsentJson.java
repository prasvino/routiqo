package com.routiqo.core.privacy.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class BrowserConsentJson {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private BrowserConsentJson() {}

    static Intent intent(HttpServletRequest request) {
        try {
            JsonNode node = MAPPER.readTree(request.getInputStream());
            if (node == null || !node.isObject()
                    || !node.propertyNames().equals(Set.of("expectedGeneration", "sharing"))) {
                throw malformed();
            }
            JsonNode generation = node.get("expectedGeneration");
            JsonNode sharing = node.get("sharing");
            if (generation == null || !generation.isTextual()
                    || sharing == null || !sharing.isBoolean()) {
                throw malformed();
            }
            String raw = generation.textValue();
            if (!raw.matches("0|[1-9][0-9]{0,18}")) {
                throw malformed();
            }
            long expected;
            try {
                expected = Long.parseLong(raw);
            } catch (NumberFormatException invalid) {
                throw malformed();
            }
            return new Intent(expected, sharing.booleanValue());
        } catch (IOException | RuntimeException invalid) {
            throw malformed();
        }
    }

    record Intent(long expectedGeneration, boolean sharing) {
        @Override public String toString() { return "BrowserConsentIntent[private]"; }
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("Invalid consent request");
    }
}
