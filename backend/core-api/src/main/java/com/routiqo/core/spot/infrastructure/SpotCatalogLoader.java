package com.routiqo.core.spot.infrastructure;

import com.routiqo.core.routing.domain.RouteRequest;
import com.routiqo.core.spot.domain.Spot;
import com.routiqo.core.spot.domain.SpotCatalog;
import com.routiqo.core.spot.domain.SpotCategory;
import com.routiqo.core.spot.domain.SpotCorridor;
import com.routiqo.core.spot.domain.SpotKind;
import com.routiqo.core.spot.domain.SpotProvenance;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict bounded loader for the curated {@code routiqo-spots/1} catalog (SPOTS_SPEC, ADR 0070), modelled
 * on the route-anchor catalog loader. Any problem rejects the whole file with one generic error that
 * never echoes file content.
 */
public final class SpotCatalogLoader {
    static final String SCHEMA = "routiqo-spots/1";
    static final int MAX_BYTES = 256 * 1024;
    private static final UUID NIL_ID = new UUID(0, 0);
    private static final Set<String> ROOT_KEYS = Set.of("schema", "version", "corridors", "spots");
    private static final Set<String> CORRIDOR_KEYS = Set.of("id", "name");
    private static final Set<String> SPOT_KEYS = Set.of("id", "name", "nameTa", "kind", "longitude",
            "latitude", "district", "corridors", "categories", "provenance");
    private static final Set<String> PROVENANCE_KEYS = Set.of("curator", "source", "reviewedAt");
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public SpotCatalog load(Path path) {
        try {
            if (path == null || !Files.isRegularFile(path)) throw invalid();
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bounded(path))).toString();
            JsonNode root = MAPPER.readTree(json);
            object(root, ROOT_KEYS);
            if (!SCHEMA.equals(text(root.get("schema")))) throw invalid();
            UUID version = uuid(root.get("version"));
            JsonNode corridorNodes = array(root.get("corridors"), SpotCatalog.MAX_CORRIDORS);
            var corridors = new ArrayList<SpotCorridor>(corridorNodes.size());
            for (JsonNode node : corridorNodes) {
                object(node, CORRIDOR_KEYS);
                corridors.add(new SpotCorridor(text(node.get("id")), text(node.get("name"))));
            }
            JsonNode spotNodes = array(root.get("spots"), SpotCatalog.MAX_SPOTS);
            var spots = new ArrayList<Spot>(spotNodes.size());
            for (JsonNode node : spotNodes) spots.add(spot(node));
            return new SpotCatalog(version, corridors, spots);
        } catch (RuntimeException | java.io.IOException invalid) {
            throw invalid();
        }
    }

    private static Spot spot(JsonNode node) {
        object(node, SPOT_KEYS);
        JsonNode longitude = node.get("longitude");
        JsonNode latitude = node.get("latitude");
        if (!longitude.isNumber() || !latitude.isNumber()) throw invalid();
        var corridors = new ArrayList<String>();
        for (JsonNode corridor : array(node.get("corridors"), SpotCatalog.MAX_CORRIDORS))
            corridors.add(text(corridor));
        JsonNode categoryNodes = array(node.get("categories"), SpotCategory.values().length);
        var categories = EnumSet.noneOf(SpotCategory.class);
        for (JsonNode category : categoryNodes)
            if (!categories.add(SpotCategory.fromKey(text(category)))) throw invalid();
        return new Spot(uuid(node.get("id")), text(node.get("name")), text(node.get("nameTa")),
                SpotKind.fromKey(text(node.get("kind"))),
                new RouteRequest.Coordinate(longitude.doubleValue(), latitude.doubleValue()),
                text(node.get("district")), List.copyOf(corridors), categories,
                provenance(node.get("provenance")));
    }

    private static SpotProvenance provenance(JsonNode node) {
        object(node, PROVENANCE_KEYS);
        return new SpotProvenance(text(node.get("curator")),
                SpotProvenance.Source.fromKey(text(node.get("source"))),
                LocalDate.parse(text(node.get("reviewedAt")), DATE));
    }

    private static void object(JsonNode node, Set<String> keys) {
        if (node == null || !node.isObject() || !node.propertyNames().equals(keys)) throw invalid();
    }

    private static JsonNode array(JsonNode node, int max) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > max) throw invalid();
        return node;
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalid();
        return node.textValue();
    }

    private static UUID uuid(JsonNode node) {
        String raw = text(node);
        if (!raw.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid();
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
        return new IllegalStateException("Spot catalog is not configured");
    }

    @Override public String toString() { return "SpotCatalogLoader[private]"; }
}
