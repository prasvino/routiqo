package com.routiqo.core.spot.api;

import com.routiqo.core.routing.domain.RouteRequest;
import com.routiqo.core.spot.domain.Spot;
import com.routiqo.core.spot.domain.SpotCatalog;
import com.routiqo.core.spot.domain.SpotCategory;
import com.routiqo.core.spot.domain.SpotCorridor;
import com.routiqo.core.spot.domain.SpotKind;
import com.routiqo.core.spot.domain.SpotProvenance;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublishedSpotCatalogTest {
    private static final UUID VERSION = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

    private static SpotCatalog catalog(int count, String name, String nameTa) {
        var spots = new ArrayList<Spot>();
        for (int index = 0; index < count; index++)
            spots.add(new Spot(new UUID(0x4000L, index + 1L), name, nameTa, SpotKind.EATERY,
                    new RouteRequest.Coordinate(79.9, 12.7), "chengalpattu", List.of("gst-trunk"),
                    EnumSet.allOf(SpotCategory.class),
                    new SpotProvenance("Private Curator Marker", SpotProvenance.Source.OSM, LocalDate.of(2026, 9, 1))));
        return new SpotCatalog(VERSION, List.of(new SpotCorridor("gst-trunk", "GST Road")), spots);
    }

    @Test void servesAStableProjectionWithoutProvenanceAndAVersionEtag() {
        var published = new PublishedSpotCatalog(catalog(2, "Toll", "சுங்கம்"));
        String body = new String(published.body(), StandardCharsets.UTF_8);

        assertThat(published.etag()).isEqualTo("\"" + VERSION + "\"");
        assertThat(body).doesNotContain("Private Curator Marker", "provenance", "osm");
        assertThat(body).contains("\"categories\":[\"traffic\",\"queue\",\"food\",\"fuel\",\"restroom\"]");
        assertThat(published.digest()).matches("[0-9a-f]{64}")
                .isEqualTo(new PublishedSpotCatalog(catalog(2, "Toll", "சுங்கம்")).digest());
        assertThat(published.matches(Collections.enumeration(List.of("W/" + published.etag())))).isTrue();
        assertThat(published.matches(Collections.enumeration(List.of("\"" + UUID.randomUUID() + "\"")))).isFalse();
        assertThat(published.matches(Collections.enumeration(List.of("x".repeat(513) + published.etag()))))
                .isFalse();
    }

    @Test void aLoaderValidCatalogOverTheResponseCapCannotBePublished() {
        // 512 Spots with 80-code-point Tamil names (3 bytes each) exceed 256 KiB once serialized.
        String tamil = "த".repeat(80);
        assertThatThrownBy(() -> new PublishedSpotCatalog(catalog(512, "x".repeat(80), tamil)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Spot catalog is not configured");
    }
}
