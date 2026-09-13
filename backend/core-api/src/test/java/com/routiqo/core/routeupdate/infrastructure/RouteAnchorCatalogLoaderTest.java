package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.routeupdate.domain.QuickSignalValue.Category;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class RouteAnchorCatalogLoaderTest {
    private static final String VERSION = "00000000-0000-4000-8000-000000000001";
    private static final String ID = "abcdefab-cdef-4abc-8def-abcdefabcdef";
    @TempDir Path directory;
    private final RouteAnchorCatalogLoader loader = new RouteAnchorCatalogLoader();

    @Test void loadsAnExactImmutableCatalogWithClosedCategories() throws Exception {
        var catalog = loader.load(write("valid.json", catalog(anchor(ID, 80, 13,
                "[\"QUEUE\",\"RESTROOM\"]"))));

        assertThat(catalog.version().toString()).isEqualTo(VERSION);
        assertThat(catalog.anchors()).hasSize(1);
        assertThat(catalog.anchors().getFirst().anchorId().toString()).isEqualTo(ID);
        assertThat(catalog.anchors().getFirst().location().longitude()).isEqualTo(80);
        assertThat(catalog.anchors().getFirst().categories())
                .containsExactlyInAnyOrder(Category.QUEUE, Category.RESTROOM);
        assertThat(loader.toString()).isEqualTo("RouteAnchorCatalogLoader[private]");
    }

    @Test void rejectsUnknownDuplicateMissingNullAndTrailingJsonWithoutLeakingInput() throws Exception {
        for (String invalid : List.of(
                "{\"version\":\"" + VERSION + "\",\"version\":\"" + VERSION + "\",\"anchors\":[]}",
                "{\"version\":\"" + VERSION + "\",\"anchors\":[],\"secret\":\"private-marker\"}",
                "{\"version\":\"" + VERSION + "\"}",
                "{\"version\":null,\"anchors\":[]}",
                catalog(anchor(ID, 80, 13, "[\"QUEUE\"]")) + " {}",
                catalog("{\"id\":\"" + ID + "\",\"longitude\":80,\"latitude\":13,"
                        + "\"categories\":[\"QUEUE\"],\"label\":\"private-marker\"}"),
                catalog("{\"id\":\"" + ID + "\",\"id\":\"" + ID + "\",\"longitude\":80,"
                        + "\"latitude\":13,\"categories\":[\"QUEUE\"]}"))) {
            assertInvalid(write("invalid-" + Math.abs(invalid.hashCode()) + ".json", invalid),
                    "private-marker", ID, VERSION);
        }
    }

    @Test void rejectsInvalidIdentifiersCoordinatesCategoriesAndDuplicates() throws Exception {
        String other = "00000000-0000-4000-8000-000000000003";
        for (String invalid : List.of(
                catalog(anchor("00000000-0000-0000-0000-000000000000", 80, 13, "[\"QUEUE\"]")),
                catalog(anchor(ID.toUpperCase(), 80, 13, "[\"QUEUE\"]")),
                catalog(anchor(ID, 181, 13, "[\"QUEUE\"]")),
                catalog(anchor(ID, 80, 91, "[\"QUEUE\"]")),
                catalog(anchor(ID, 80, 13, "[]")),
                catalog(anchor(ID, 80, 13, "[\"QUEUE\",\"QUEUE\"]")),
                catalog(anchor(ID, 80, 13, "[\"UNKNOWN_PRIVATE_CATEGORY\"]")),
                catalog(anchor(ID, 80, 13, "[1]")),
                catalog(anchor(ID, 80, 13, "null")),
                catalog(anchor(ID, 80, 13, "[\"QUEUE\"]") + ","
                        + anchor(ID, 80.1, 13.1, "[\"TRAFFIC\"]")),
                "{\"version\":\"" + other + "\",\"anchors\":[{\"id\":\"" + ID
                        + "\",\"longitude\":1e309,\"latitude\":13,"
                        + "\"categories\":[\"QUEUE\"]}]}")) {
            assertInvalid(write("invalid-value-" + Math.abs(invalid.hashCode()) + ".json", invalid),
                    "UNKNOWN_PRIVATE_CATEGORY", ID);
        }
    }

    @Test void enforcesOneToFiveHundredTwelveAnchors() throws Exception {
        assertInvalid(write("empty.json", catalog("")), VERSION);
        var anchors = new ArrayList<String>();
        for (int index = 1; index <= 513; index++) {
            String id = String.format("00000000-0000-4000-8000-%012d", index);
            anchors.add(anchor(id, 80, 13, "[\"QUEUE\"]"));
        }
        assertThat(loader.load(write("max.json", catalog(String.join(",", anchors.subList(0, 512)))))
                .anchors()).hasSize(512);
        assertInvalid(write("too-many.json", catalog(String.join(",", anchors))), VERSION);
    }

    @Test void appliesTheByteBoundBeforeParsingAndRejectsMalformedUtf8() throws Exception {
        String base = catalog(anchor(ID, 80, 13, "[\"QUEUE\"]"));
        byte[] exact = (base + " ".repeat(RouteAnchorCatalogLoader.MAX_BYTES
                - base.getBytes(StandardCharsets.UTF_8).length)).getBytes(StandardCharsets.UTF_8);
        Path exactPath = directory.resolve("exact.json");
        Files.write(exactPath, exact);
        assertThat(loader.load(exactPath).anchors()).hasSize(1);

        Path oversized = directory.resolve("oversized.json");
        Files.write(oversized, java.util.Arrays.copyOf(exact, exact.length + 1));
        assertInvalid(oversized, ID, VERSION);

        Path malformed = directory.resolve("malformed.json");
        Files.write(malformed, new byte[] {'{', '}', (byte) 0xc3, (byte) 0x28});
        assertInvalid(malformed);
        assertInvalid(directory.resolve("missing-private-name.json"), "missing-private-name");
        assertInvalid(directory, directory.toString());
    }

    private Path write(String name, String json) throws Exception {
        Path path = directory.resolve(name);
        Files.writeString(path, json, StandardCharsets.UTF_8);
        return path;
    }

    private void assertInvalid(Path path, String... privateValues) {
        Throwable failure = catchThrowable(() -> loader.load(path));
        assertThat(failure).isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Route anchor catalog is not configured").hasNoCause();
        if (privateValues.length > 0) assertThat(failure.toString()).doesNotContain(privateValues);
    }

    private static String catalog(String anchors) {
        return "{\"version\":\"" + VERSION + "\",\"anchors\":[" + anchors + "]}";
    }

    private static String anchor(String id, double longitude, double latitude, String categories) {
        return "{\"id\":\"" + id + "\",\"longitude\":" + longitude
                + ",\"latitude\":" + latitude + ",\"categories\":" + categories + "}";
    }
}
