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

    /**
     * Serializes activity within the 128 KiB cap. If it would not fit, the oldest posts across the
     * response are dropped and their Spots marked `postsTruncated`; signal summaries always stay.
     */
    static byte[] activityResponse(SpotActivity activity) {
        List<SpotActivity.Entry> entries = new ArrayList<>(activity.spots());
        byte[] body = write(activity, entries);
        while (body.length > MAX_RESPONSE_BYTES) {
            int dropFrom = -1;
            java.time.Instant oldest = null;
            for (int index = 0; index < entries.size(); index++) {
                List<SpotActivity.PostView> posts = entries.get(index).posts();
                if (posts.isEmpty()) continue;
                var last = posts.getLast().capturedAt();
                if (oldest == null || last.isBefore(oldest)) {
                    oldest = last;
                    dropFrom = index;
                }
            }
            if (dropFrom < 0) throw new ResponseTooLarge();
            var entry = entries.get(dropFrom);
            entries.set(dropFrom, entry.withPosts(entry.posts().subList(0, entry.posts().size() - 1), true));
            body = write(activity, entries);
        }
        return body;
    }

    private static byte[] write(SpotActivity activity, List<SpotActivity.Entry> entries) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("serverTime", activity.serverTime().toString());
        root.put("catalogVersion", activity.catalogVersion().toString());
        ArrayNode spots = root.putArray("spots");
        for (SpotActivity.Entry entry : entries) {
            ObjectNode spot = spots.addObject();
            spot.put("id", entry.id().toString());
            spot.put("state", entry.state().key());
            spot.putArray("alertIds");
            ArrayNode signals = spot.putArray("signals");
            for (var summary : entry.signals()) {
                ObjectNode node = signals.addObject();
                node.put("ref", summary.ref().toString());
                node.put("category", summary.category());
                node.put("value", summary.topValue());
                ArrayNode values = node.putArray("values");
                for (var count : summary.values())
                    values.addObject().put("value", count.value()).put("reports", count.reports());
                node.put("latestAt", summary.latestAt().toString());
                node.put("stillTrue", summary.stillTrue());
                if (summary.viewerVote() == null) node.putNull("viewerVote");
                else node.put("viewerVote", summary.viewerVote());
            }
            ArrayNode posts = spot.putArray("posts");
            for (var post : entry.posts()) {
                ObjectNode node = posts.addObject();
                node.put("ref", post.ref().toString());
                node.put("alias", post.alias());
                node.put("text", post.text());
                node.put("type", post.type());
                node.put("capturedAt", post.capturedAt().toString());
                node.put("expiresAt", post.expiresAt().toString());
                node.put("stillTrue", post.stillTrue());
                if (post.viewerVote() == null) node.putNull("viewerVote");
                else node.put("viewerVote", post.viewerVote());
                node.put("mine", post.mine());
            }
            spot.put("postsTruncated", entry.postsTruncated());
            ArrayNode highlights = spot.putArray("highlights");
            for (var highlight : entry.highlights())
                highlights.addObject().put("text", highlight.text()).put("createdAt", highlight.createdAt().toString());
        }
        root.putArray("alerts");
        return MAPPER.writeValueAsBytes(root);
    }

    static final class ResponseTooLarge extends RuntimeException {
        ResponseTooLarge() { super(null, null, false, false); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid native Spot activity request");
    }
}
