package com.routiqo.core.spot.infrastructure;

import com.routiqo.core.spot.domain.SpotCategory;
import com.routiqo.core.spot.domain.SpotKind;
import com.routiqo.core.spot.domain.SpotProvenance;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class SpotCatalogLoaderTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String VERSION = "00000000-0000-4000-8000-0000000000aa";
    private static final String SPOT_ID = "abcdefab-cdef-4abc-8def-abcdefabcdef";
    private static final String TAMIL = "செங்கல்பட்டு சுங்கச்சாவடி";
    @TempDir Path directory;
    private final SpotCatalogLoader loader = new SpotCatalogLoader(
            java.time.Clock.fixed(java.time.Instant.parse("2026-10-25T00:00:00Z"), java.time.ZoneOffset.UTC));

    private static ObjectNode spot(String id) {
        ObjectNode spot = MAPPER.createObjectNode();
        spot.put("id", id);
        spot.put("name", "Chengalpattu Toll Plaza");
        spot.put("nameTa", TAMIL);
        spot.put("kind", "toll");
        spot.put("longitude", 79.95);
        spot.put("latitude", 12.69);
        spot.put("district", "chengalpattu");
        spot.putArray("corridors").add("gst-trunk");
        spot.putArray("categories").add("traffic").add("queue");
        ObjectNode provenance = spot.putObject("provenance");
        provenance.put("curator", "Test Curator");
        provenance.put("source", "field_visit");
        provenance.put("reviewedAt", "2026-10-20");
        return spot;
    }

    private static ObjectNode catalog() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema", "routiqo-spots/1");
        root.put("version", VERSION);
        root.putArray("corridors").addObject().put("id", "gst-trunk").put("name", "Chennai – Trichy (GST Road)");
        root.putArray("spots").add(spot(SPOT_ID));
        return root;
    }

    private static ObjectNode firstSpot(ObjectNode root) { return (ObjectNode) root.get("spots").get(0); }

    private Path write(String name, String json) throws Exception {
        return Files.writeString(directory.resolve(name), json, StandardCharsets.UTF_8);
    }

    private void rejects(String name, Consumer<ObjectNode> change) throws Exception {
        ObjectNode root = catalog();
        change.accept(root);
        rejectsRaw(name, MAPPER.writeValueAsString(root));
    }

    private void rejectsRaw(String name, String json) throws Exception {
        Throwable failure = catchThrowable(() -> loader.load(write(name + ".json", json)));
        assertThat(failure).as(name).isInstanceOf(IllegalStateException.class)
                .hasMessage("Spot catalog is not configured").hasNoCause();
    }

    @Test void loadsAValidBilingualCatalogWithProvenance() throws Exception {
        var catalog = loader.load(write("valid.json", MAPPER.writeValueAsString(catalog())));

        assertThat(catalog.version().toString()).isEqualTo(VERSION);
        assertThat(catalog.corridors()).singleElement()
                .satisfies(corridor -> assertThat(corridor.id()).isEqualTo("gst-trunk"));
        var spot = catalog.spots().getFirst();
        assertThat(spot.id().toString()).isEqualTo(SPOT_ID);
        assertThat(spot.name()).isEqualTo("Chengalpattu Toll Plaza");
        assertThat(spot.nameTa()).isEqualTo(TAMIL);
        assertThat(spot.kind()).isEqualTo(SpotKind.TOLL);
        assertThat(spot.location().longitude()).isEqualTo(79.95);
        assertThat(spot.district()).isEqualTo("chengalpattu");
        assertThat(spot.categories()).containsExactlyInAnyOrder(SpotCategory.TRAFFIC, SpotCategory.QUEUE);
        assertThat(spot.provenance().source()).isEqualTo(SpotProvenance.Source.FIELD_VISIT);
        assertThat(spot.provenance().reviewedAt()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(spot.provenance().toString()).doesNotContain("Test Curator");
        assertThat(catalog.toString()).isEqualTo("SpotCatalog[private]");
    }

    @Test void acceptsEveryKindAndTheMaximumSpotCount() throws Exception {
        ObjectNode root = catalog();
        ArrayNode spots = root.putArray("spots");
        String[] kinds = {"toll", "eatery", "fuel", "restroom", "bus_stand", "temple", "junction", "rest_area"};
        for (int index = 0; index < 512; index++) {
            ObjectNode spot = spot(String.format("00000000-0000-4000-8000-%012x", index + 1));
            spot.put("kind", kinds[index % kinds.length]);
            spots.add(spot);
        }
        assertThat(loader.load(write("max.json", MAPPER.writeValueAsString(root))).spots()).hasSize(512);
    }

    @Test void rejectsUnknownMissingAndDuplicateKeys() throws Exception {
        rejects("unknown-root", root -> root.put("extra", "private-marker"));
        rejects("unknown-spot", root -> firstSpot(root).put("phone", "9999999999"));
        rejects("unknown-provenance", root -> ((ObjectNode) firstSpot(root).get("provenance")).put("x", 1));
        rejects("unknown-corridor", root -> ((ObjectNode) root.get("corridors").get(0)).put("x", 1));
        rejects("missing-provenance", root -> firstSpot(root).remove("provenance"));
        rejects("missing-curator", root -> ((ObjectNode) firstSpot(root).get("provenance")).remove("curator"));
        rejects("missing-name", root -> firstSpot(root).remove("name"));
        rejects("missing-tamil", root -> firstSpot(root).remove("nameTa"));
        rejects("missing-schema", root -> root.remove("schema"));
        rejectsRaw("duplicate-json-key", MAPPER.writeValueAsString(catalog())
                .replace("\"schema\":\"routiqo-spots/1\"",
                        "\"schema\":\"routiqo-spots/1\",\"schema\":\"routiqo-spots/1\""));
    }

    @Test void rejectsBadNamesIdsAndDuplicates() throws Exception {
        rejects("empty-tamil", root -> firstSpot(root).put("nameTa", ""));
        rejects("null-tamil", root -> firstSpot(root).putNull("nameTa"));
        rejects("padded-name", root -> firstSpot(root).put("name", " Toll"));
        rejects("double-space", root -> firstSpot(root).put("name", "Toll  Plaza"));
        rejects("control-name", root -> firstSpot(root).put("name", "Toll\nPlaza"));
        rejects("long-name", root -> firstSpot(root).put("name", "x".repeat(81)));
        rejects("upper-id", root -> firstSpot(root).put("id", SPOT_ID.toUpperCase()));
        rejects("nil-id", root -> firstSpot(root).put("id", "00000000-0000-0000-0000-000000000000"));
        rejects("nil-version", root -> root.put("version", "00000000-0000-0000-0000-000000000000"));
        rejects("duplicate-spot", root -> ((ArrayNode) root.get("spots")).add(spot(SPOT_ID)));
        rejects("duplicate-corridor", root -> ((ArrayNode) root.get("corridors")).addObject()
                .put("id", "gst-trunk").put("name", "Again"));
        rejects("bad-corridor-id", root -> ((ObjectNode) root.get("corridors").get(0)).put("id", "GST Trunk"));
    }

    @Test void rejectsUnknownKindDistrictCategoryCorridorSourceAndBadDates() throws Exception {
        rejects("wrong-schema", root -> root.put("schema", "routiqo-spots/2"));
        rejects("unknown-kind", root -> firstSpot(root).put("kind", "hotel"));
        rejects("upper-kind", root -> firstSpot(root).put("kind", "TOLL"));
        rejects("unknown-district", root -> firstSpot(root).put("district", "bengaluru"));
        rejects("unknown-category", root -> firstSpot(root).putArray("categories").add("parking"));
        rejects("duplicate-category", root -> firstSpot(root).putArray("categories").add("queue").add("queue"));
        rejects("empty-categories", root -> firstSpot(root).putArray("categories"));
        rejects("undeclared-corridor", root -> firstSpot(root).putArray("corridors").add("trichy-thanjavur"));
        rejects("duplicate-spot-corridor", root -> firstSpot(root).putArray("corridors")
                .add("gst-trunk").add("gst-trunk"));
        rejects("unknown-source", root -> ((ObjectNode) firstSpot(root).get("provenance")).put("source", "user"));
        rejects("bad-date", root -> ((ObjectNode) firstSpot(root).get("provenance")).put("reviewedAt", "2026-02-30"));
        rejects("loose-date", root -> ((ObjectNode) firstSpot(root).get("provenance")).put("reviewedAt", "2026-1-5"));
        rejects("future-review", root -> ((ObjectNode) firstSpot(root).get("provenance")).put("reviewedAt", "2026-10-27"));
        assertThat(loader.load(write("tomorrow.json", MAPPER.writeValueAsString(withReview("2026-10-26"))))
                .spots()).hasSize(1);
        rejects("string-longitude", root -> firstSpot(root).put("longitude", "79.95"));
        rejects("out-of-range-latitude", root -> firstSpot(root).put("latitude", 91));
    }

    private static ObjectNode withReview(String date) {
        ObjectNode root = catalog();
        ((ObjectNode) firstSpot(root).get("provenance")).put("reviewedAt", date);
        return root;
    }

    @Test void rejectsContactDetailsInNamesAndTamilNamesWithoutTamilScript() throws Exception {
        for (String name : new String[] {"Hotel 9876543210", "Hotel 98765 43210", "Call 044-2345-678",
                "Visit www.example", "Hotel example.com", "http://x", "HTTPS: Stop", "a@b"}) {
            rejects("contact-en-" + name.hashCode(), root -> firstSpot(root).put("name", name));
            rejects("contact-ta-" + name.hashCode(), root -> firstSpot(root).put("nameTa", TAMIL + " " + name));
        }
        rejects("latin-tamil", root -> firstSpot(root).put("nameTa", "Chengalpattu Toll"));
        ObjectNode ok = catalog();
        firstSpot(ok).put("name", "NH 32 Km 45 Toll, Gate 2");
        assertThat(loader.load(write("digits-ok.json", MAPPER.writeValueAsString(ok))).spots()).hasSize(1);
    }

    @Test void rejectsEmptyTooManyOversizeMalformedAndMissingFiles() throws Exception {
        rejects("no-spots", root -> root.putArray("spots"));
        rejects("no-corridors", root -> root.putArray("corridors"));
        rejects("too-many-spots", root -> {
            ArrayNode spots = root.putArray("spots");
            for (int index = 0; index < 513; index++)
                spots.add(spot(String.format("00000000-0000-4000-8000-%012x", index + 1)));
        });
        String valid = MAPPER.writeValueAsString(catalog());
        rejectsRaw("oversize", valid.substring(0, valid.length() - 1)
                + " ".repeat(SpotCatalogLoader.MAX_BYTES) + "}");
        rejectsRaw("trailing", valid + "{}");
        rejectsRaw("array-root", "[" + valid + "]");
        Path invalidUtf8 = directory.resolve("invalid-utf8.json");
        byte[] bytes = valid.getBytes(StandardCharsets.UTF_8);
        bytes[bytes.length - 3] = (byte) 0xC3;
        Files.write(invalidUtf8, bytes);
        assertThat(catchThrowable(() -> loader.load(invalidUtf8))).isInstanceOf(IllegalStateException.class);
        assertThat(catchThrowable(() -> loader.load(directory.resolve("missing.json"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(catchThrowable(() -> loader.load(directory))).isInstanceOf(IllegalStateException.class);
    }
}
