package com.routiqo.core.routeupdate.api;

import com.routiqo.core.routeupdate.application.SignalCommandPolicy;
import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.SignalIssuanceExpectation;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class BrowserSignalJson {
    private static final UUID NIL_ID = new UUID(0, 0);
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private BrowserSignalJson() {}

    static UUID issue(HttpServletRequest request) {
        JsonNode node = object(request, Set.of("anchorId"));
        return id(text(node, "anchorId"));
    }

    static ExpectedIssue expectedIssue(HttpServletRequest request) {
        JsonNode node = object(request, Set.of(
                "anchorId", "contextId", "routeRevision", "consentGeneration"));
        UUID anchorId = id(text(node, "anchorId"));
        var expectation = new SignalIssuanceExpectation(
                id(text(node, "contextId")), decimal(text(node, "routeRevision")),
                decimal(text(node, "consentGeneration")));
        return new ExpectedIssue(anchorId, expectation);
    }

    static Acceptance acceptance(UUID journeyId, HttpServletRequest request) {
        JsonNode node = object(request, Set.of("anchorId", "value", "contextId",
                "routeRevision", "consentGeneration"));
        UUID anchorId = id(text(node, "anchorId"));
        UUID contextId = id(text(node, "contextId"));
        QuickSignalValue value = value(text(node, "value"));
        long routeRevision = decimal(text(node, "routeRevision"));
        long consentGeneration = decimal(text(node, "consentGeneration"));
        return new Acceptance(new SignalCommandPolicy.SubmissionFingerprint(journeyId, anchorId,
                value, consentGeneration, contextId, routeRevision));
    }

    static void empty(HttpServletRequest request) {
        object(request, Set.of());
    }

    private static JsonNode object(HttpServletRequest request, Set<String> properties) {
        try {
            byte[] bytes = request.getInputStream().readAllBytes();
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject() || !node.propertyNames().equals(properties)) {
                throw malformed();
            }
            return node;
        } catch (IOException | RuntimeException invalid) {
            throw malformed();
        }
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) throw malformed();
        return value.textValue();
    }

    private static UUID id(String value) {
        if (value == null || !value.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw malformed();
        }
        UUID id = UUID.fromString(value);
        if (NIL_ID.equals(id)) throw malformed();
        return id;
    }

    private static long decimal(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw malformed();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw malformed();
        }
    }

    private static QuickSignalValue value(String raw) {
        try {
            QuickSignalValue parsed = QuickSignalValue.valueOf(raw.toUpperCase(Locale.ROOT));
            if (!parsed.name().toLowerCase(Locale.ROOT).equals(raw)) throw malformed();
            return parsed;
        } catch (IllegalArgumentException invalid) {
            throw malformed();
        }
    }

    record Acceptance(SignalCommandPolicy.SubmissionFingerprint fingerprint) {
        @Override public String toString() { return "BrowserSignalAcceptance[private]"; }
    }

    record ExpectedIssue(UUID anchorId, SignalIssuanceExpectation expectation) {
        @Override public String toString() { return "BrowserExpectedSignalIssue[private]"; }
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("Invalid signal request");
    }
}
