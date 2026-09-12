package com.routiqo.core.routing;

import com.routiqo.core.routing.domain.*;
import com.routiqo.core.routing.application.PlaceProvider;
import com.routiqo.core.routing.infrastructure.MapboxPlaceProvider;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MapboxPlaceProviderTest {
    private static final String FEATURE = """
        {"type":"Feature","geometry":{"type":"Point","coordinates":[80,13]},
         "properties":{"mapbox_id":"synthetic-place","full_address":"Synthetic town","private":"discard"}}
        """;
    private static String collection(String features) {
        return "{\"type\":\"FeatureCollection\",\"attribution\":\"Synthetic attribution\",\"features\":[" + features + "]}";
    }
    @Test void encodesPrivateQueryAndRequestsOnlyTemporaryExplicitResults() {
        var captured = new AtomicReference<URI>();
        var provider = new MapboxPlaceProvider("synthetic-token", uri -> { captured.set(uri); return collection(FEATURE); });
        assertThat(provider.identity()).isEqualTo(PlaceProvider.Identity.MAPBOX);
        var query = new PlaceQuery("  Chennai & street?limit=999  ");
        var result = provider.search(query);
        assertThat(captured.get().getHost()).isEqualTo("api.mapbox.com");
        assertThat(captured.get().getPath()).isEqualTo("/search/geocode/v6/forward");
        assertThat(captured.get().getRawQuery()).contains("q=Chennai+%26+street%3Flimit%3D999", "autocomplete=false", "permanent=false", "&limit=5&");
        assertThat(result.places()).hasSize(1);
        assertThat(result.places().getFirst().coordinate().longitude()).isEqualTo(80);
        assertThat(result.attribution()).isEqualTo("Synthetic attribution");
        assertThat(query.toString()).doesNotContain("Chennai");
        assertThat(result.toString()).doesNotContain("Synthetic town");
    }
    @Test void rejectsInvalidQueriesAndResultsWithoutLeakingOriginalFailure() {
        for (String query : List.of("ab", "street;town", "street\ntown", "word ".repeat(21), "x".repeat(257), "..."))
            assertThatThrownBy(() -> new PlaceQuery(query)).isInstanceOf(IllegalArgumentException.class);
        for (String raw : List.of(collection(FEATURE.replace("[80,13]", "[80,91]")), collection(FEATURE + "," + FEATURE),
                collection(FEATURE.replace("\"full_address\":\"Synthetic town\"", "\"full_address\":7")), "x".repeat(262145))) {
            var provider = new MapboxPlaceProvider("synthetic-token", uri -> raw);
            assertThatThrownBy(() -> provider.search(new PlaceQuery("Chennai")))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Place search is temporarily unavailable").hasNoCause();
        }
        var provider = new MapboxPlaceProvider("synthetic-token", uri -> { throw new Exception(uri.toString()); });
        assertThatThrownBy(() -> provider.search(new PlaceQuery("Chennai")))
                .hasMessage("Place search is temporarily unavailable").hasNoCause();
    }
    @Test void distinguishesNoMatchesFromFailure() {
        assertThat(new MapboxPlaceProvider("synthetic-token", uri -> collection("")).search(new PlaceQuery("Chennai")).places()).isEmpty();
        assertThatThrownBy(() -> new MapboxPlaceProvider("synthetic-token", uri -> "{}").search(new PlaceQuery("Chennai")))
                .isInstanceOf(IllegalStateException.class);
    }
}
