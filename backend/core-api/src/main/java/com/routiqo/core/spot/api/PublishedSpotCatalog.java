package com.routiqo.core.spot.api;

import com.routiqo.core.spot.domain.Spot;
import com.routiqo.core.spot.domain.SpotCatalog;
import com.routiqo.core.spot.domain.SpotCategory;
import com.routiqo.core.spot.domain.SpotCorridor;
import java.util.Comparator;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The public catalog response, serialized once at startup. Provenance is never included. The ETag is
 * the quoted catalog version, so every replica loading the same file serves the same tag.
 */
final class PublishedSpotCatalog {
    static final int MAX_BYTES = 256 * 1024;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final byte[] body;
    private final String etag;

    PublishedSpotCatalog(SpotCatalog catalog) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", catalog.version().toString());
        ArrayNode corridors = root.putArray("corridors");
        for (SpotCorridor corridor : catalog.corridors())
            corridors.addObject().put("id", corridor.id()).put("name", corridor.name());
        ArrayNode spots = root.putArray("spots");
        for (Spot spot : catalog.spots()) {
            ObjectNode node = spots.addObject();
            node.put("id", spot.id().toString());
            node.put("name", spot.name());
            node.put("nameTa", spot.nameTa());
            node.put("kind", spot.kind().key());
            node.put("longitude", spot.location().longitude());
            node.put("latitude", spot.location().latitude());
            node.put("district", spot.district());
            ArrayNode spotCorridors = node.putArray("corridors");
            spot.corridors().forEach(spotCorridors::add);
            ArrayNode categories = node.putArray("categories");
            spot.categories().stream().sorted(Comparator.naturalOrder()).map(SpotCategory::key)
                    .forEach(categories::add);
        }
        byte[] serialized = MAPPER.writeValueAsBytes(root);
        if (serialized.length > MAX_BYTES) throw new IllegalStateException("Spot catalog is not configured");
        this.body = serialized;
        this.etag = "\"" + catalog.version() + "\"";
    }

    byte[] body() { return body.clone(); }

    String etag() { return etag; }

    /** RFC 9110 weak comparison over a comma-separated If-None-Match list, including {@code *}. */
    boolean matches(java.util.Enumeration<String> ifNoneMatch) {
        while (ifNoneMatch != null && ifNoneMatch.hasMoreElements()) {
            String value = ifNoneMatch.nextElement();
            if (value == null || value.length() > 512) return false;
            for (String candidate : value.split(",", -1)) {
                String tag = candidate.strip();
                if (tag.startsWith("W/")) tag = tag.substring(2);
                if (tag.equals("*") || tag.equals(etag)) return true;
            }
        }
        return false;
    }
}
