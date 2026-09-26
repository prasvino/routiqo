package com.routiqo.core.spot.api;

import com.routiqo.core.spot.application.SpotContributionService;
import com.routiqo.core.spot.application.SpotContributionStore;
import com.routiqo.core.spot.application.SpotReportService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Strict, hand-parsed contribution bodies and receipts. Post text and Spot IDs never pass through
 * Spring's message converters, so framework DEBUG logging cannot record them.
 */
final class NativeSpotContributionJson {
    private static final int MAX_BYTES = 20 * 1024;
    private static final String CANONICAL_ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z";
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private NativeSpotContributionJson() {}

    static SpotContributionService.SignalCommand signal(HttpServletRequest request) {
        JsonNode node = object(request, Set.of("clientKey", "spotId", "category", "value", "capturedAt", "journeyId"));
        return new SpotContributionService.SignalCommand(id(node.get("clientKey")), id(node.get("spotId")),
                text(node.get("category"), 16), text(node.get("value"), 16), instant(node.get("capturedAt")),
                id(node.get("journeyId")));
    }

    static SpotContributionService.PostCommand post(HttpServletRequest request) {
        JsonNode node = object(request, Set.of("clientKey", "spotId", "type", "text", "capturedAt", "journeyId"));
        return new SpotContributionService.PostCommand(id(node.get("clientKey")), id(node.get("spotId")),
                text(node.get("type"), 16), text(node.get("text"), 2000), instant(node.get("capturedAt")),
                id(node.get("journeyId")));
    }

    static SpotContributionStore.VoteKind vote(HttpServletRequest request) {
        JsonNode node = object(request, Set.of("vote"));
        return switch (text(node.get("vote"), 16)) {
            case "still_true" -> SpotContributionStore.VoteKind.STILL_TRUE;
            case "no_longer_true" -> SpotContributionStore.VoteKind.NO_LONGER_TRUE;
            default -> throw invalid();
        };
    }

    static SpotReportService.ReportCommand report(HttpServletRequest request) {
        JsonNode node = object(request, Set.of("requestId", "reason"));
        return new SpotReportService.ReportCommand(id(node.get("requestId")), text(node.get("reason"), 16));
    }

    static byte[] reportReceipt(SpotReportService.ReportReceipt receipt) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("receivedAt", receipt.receivedAt().toString());
        root.put("receiptExpiresAt", receipt.receiptExpiresAt().toString());
        return MAPPER.writeValueAsBytes(root);
    }

    static void empty(HttpServletRequest request) {
        object(request, Set.of());
    }

    static UUID ref(String raw) {
        if (raw == null || !raw.matches(CANONICAL_ID)) throw invalid();
        UUID id = UUID.fromString(raw);
        if (id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() == 0) throw invalid();
        return id;
    }

    static byte[] receipt(SpotContributionService.Receipt receipt) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("ref", receipt.ref().toString());
        root.put("status", receipt.status());
        root.put("expiresAt", receipt.expiresAt().toString());
        if (receipt.alias() != null) root.put("alias", receipt.alias());
        return MAPPER.writeValueAsBytes(root);
    }

    static byte[] vote(SpotContributionService.VoteResult result) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("ref", result.ref().toString());
        root.put("status", result.status());
        root.put("expiresAt", result.expiresAt().toString());
        root.put("stillTrue", result.stillTrue());
        return MAPPER.writeValueAsBytes(root);
    }

    private static JsonNode object(HttpServletRequest request, Set<String> keys) {
        try {
            byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw invalid();
            String raw = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode node = MAPPER.readTree(raw);
            if (node == null || !node.isObject() || !node.propertyNames().equals(keys)) throw invalid();
            return node;
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception malformed) {
            throw invalid();
        }
    }

    private static String text(JsonNode node, int maxLength) {
        if (node == null || !node.isTextual() || node.textValue().length() > maxLength) throw invalid();
        return node.textValue();
    }

    private static UUID id(JsonNode node) {
        return ref(text(node, 36));
    }

    private static Instant instant(JsonNode node) {
        String raw = text(node, 40);
        if (!raw.matches(INSTANT)) throw invalid();
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException malformed) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid native Spot contribution");
    }
}
