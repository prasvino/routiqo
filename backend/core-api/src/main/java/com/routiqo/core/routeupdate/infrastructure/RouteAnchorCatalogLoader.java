package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.routeupdate.domain.QuickSignalValue.Category;
import com.routiqo.core.routeupdate.domain.RouteAnchor;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import com.routiqo.core.routing.domain.RouteRequest;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict bounded loader for an operator-selected local route-anchor catalog. */
public final class RouteAnchorCatalogLoader {
    static final int MAX_BYTES = 256 * 1024;
    private static final UUID NIL_ID = new UUID(0, 0);
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public RouteAnchorCatalog load(Path path) {
        try {
            if (path == null || !Files.isRegularFile(path)) throw invalid();
            byte[] bytes = bounded(path);
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject()
                    || !root.propertyNames().equals(Set.of("version", "anchors"))) throw invalid();
            UUID version = uuid(root.get("version"));
            JsonNode anchorNodes = root.get("anchors");
            if (anchorNodes == null || !anchorNodes.isArray()
                    || anchorNodes.isEmpty() || anchorNodes.size() > 512) throw invalid();
            var anchors = new ArrayList<RouteAnchor>(anchorNodes.size());
            var ids = new HashSet<UUID>();
            for (JsonNode node : anchorNodes) {
                RouteAnchor anchor = anchor(node);
                if (!ids.add(anchor.anchorId())) throw invalid();
                anchors.add(anchor);
            }
            return new RouteAnchorCatalog(version, anchors);
        } catch (RuntimeException | java.io.IOException invalid) {
            throw invalid();
        }
    }

    private static RouteAnchor anchor(JsonNode node) {
        if (node == null || !node.isObject()
                || !node.propertyNames().equals(Set.of("id", "longitude", "latitude", "categories"))) {
            throw invalid();
        }
        UUID id = uuid(node.get("id"));
        JsonNode longitudeNode = node.get("longitude");
        JsonNode latitudeNode = node.get("latitude");
        JsonNode categoryNodes = node.get("categories");
        if (longitudeNode == null || !longitudeNode.isNumber()
                || latitudeNode == null || !latitudeNode.isNumber()
                || categoryNodes == null || !categoryNodes.isArray()
                || categoryNodes.isEmpty() || categoryNodes.size() > Category.values().length) {
            throw invalid();
        }
        double longitude = longitudeNode.doubleValue();
        double latitude = latitudeNode.doubleValue();
        var categories = new HashSet<Category>();
        for (JsonNode categoryNode : categoryNodes) {
            if (categoryNode == null || !categoryNode.isTextual()) throw invalid();
            Category category;
            try {
                category = Category.valueOf(categoryNode.textValue());
            } catch (IllegalArgumentException unknown) {
                throw invalid();
            }
            if (!categories.add(category)) throw invalid();
        }
        return new RouteAnchor(id, new RouteRequest.Coordinate(longitude, latitude), categories);
    }

    private static UUID uuid(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalid();
        String raw = node.textValue();
        if (raw == null || !raw.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw invalid();
        }
        UUID id = UUID.fromString(raw);
        if (NIL_ID.equals(id)) throw invalid();
        return id;
    }

    private static byte[] bounded(Path path) throws java.io.IOException {
        try (var input = Files.newInputStream(path); var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int size = 0;
            while (true) {
                int read = input.read(buffer);
                if (read < 0) break;
                size += read;
                if (size > MAX_BYTES) throw invalid();
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("Route anchor catalog is not configured");
    }

    @Override public String toString() { return "RouteAnchorCatalogLoader[private]"; }
}
