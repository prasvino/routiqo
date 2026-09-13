package com.routiqo.core.routeupdate.domain;

import com.routiqo.core.routeupdate.domain.QuickSignalValue.Category;
import com.routiqo.core.routing.domain.RouteRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class RouteAnchorCatalogTest {
    private static final UUID VERSION = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ANCHOR_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final RouteRequest.Coordinate LOCATION = new RouteRequest.Coordinate(80, 13);

    @Test void defensivelyCopiesTheCatalogCategoriesAndResolvedMapping() {
        var categories = new HashSet<>(Set.of(Category.QUEUE));
        var anchor = new RouteAnchor(ANCHOR_ID, LOCATION, categories);
        var anchors = new ArrayList<>(List.of(anchor));
        var catalog = new RouteAnchorCatalog(VERSION, anchors);
        var mapping = new HashMap<UUID, Set<Category>>();
        mapping.put(ANCHOR_ID, categories);
        var result = new ResolvedRouteAnchors(VERSION, ResolvedRouteAnchors.Status.ROUTE, mapping);

        categories.add(Category.TRAFFIC);
        anchors.clear();
        mapping.clear();

        assertThat(anchor.categories()).containsExactly(Category.QUEUE);
        assertThat(catalog.anchors()).containsExactly(anchor);
        assertThat(result.anchors()).containsOnlyKeys(ANCHOR_ID);
        assertThat(result.anchors().get(ANCHOR_ID)).containsExactly(Category.QUEUE);
        assertThatThrownBy(() -> anchor.categories().add(Category.TRAFFIC))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.anchors().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.anchors().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void validatesIdentifiersBoundsAndNoRouteShapeWithGenericDiagnostics() {
        UUID nil = new UUID(0, 0);
        RouteAnchor anchor = new RouteAnchor(ANCHOR_ID, LOCATION, Set.of(Category.QUEUE));
        List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> invalid = List.of(
                () -> new RouteAnchor(null, LOCATION, Set.of(Category.QUEUE)),
                () -> new RouteAnchor(nil, LOCATION, Set.of(Category.QUEUE)),
                () -> new RouteAnchor(ANCHOR_ID, null, Set.of(Category.QUEUE)),
                () -> new RouteAnchor(ANCHOR_ID, LOCATION, Set.of()),
                () -> new RouteAnchorCatalog(null, List.of(anchor)),
                () -> new RouteAnchorCatalog(nil, List.of(anchor)),
                () -> new RouteAnchorCatalog(VERSION, List.of()),
                () -> new RouteAnchorCatalog(VERSION, List.of(anchor, anchor)),
                () -> new ResolvedRouteAnchors(VERSION, null, Map.of()),
                () -> new ResolvedRouteAnchors(VERSION, ResolvedRouteAnchors.Status.NO_ROUTE,
                        Map.of(ANCHOR_ID, Set.of(Category.QUEUE))));
        for (var operation : invalid) {
            Throwable failure = catchThrowable(operation);
            assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasNoCause();
            assertThat(failure.toString()).doesNotContain(ANCHOR_ID.toString(), VERSION.toString(), "80", "13");
        }

        var tooManyAnchors = new ArrayList<RouteAnchor>();
        for (int index = 1; index <= 513; index++) {
            tooManyAnchors.add(new RouteAnchor(new UUID(1, index), LOCATION, Set.of(Category.QUEUE)));
        }
        assertThatThrownBy(() -> new RouteAnchorCatalog(VERSION, tooManyAnchors))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();

        var tooManyMatches = new HashMap<UUID, Set<Category>>();
        for (int index = 1; index <= 129; index++) {
            tooManyMatches.put(new UUID(2, index), Set.of(Category.QUEUE));
        }
        assertThatThrownBy(() -> new ResolvedRouteAnchors(
                VERSION, ResolvedRouteAnchors.Status.ROUTE, tooManyMatches))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @Test void redactsAllPrivateModelsAndDistinguishesNoRouteFromNoMatches() {
        RouteAnchor anchor = new RouteAnchor(ANCHOR_ID, LOCATION, Set.of(Category.QUEUE));
        RouteAnchorCatalog catalog = new RouteAnchorCatalog(VERSION, List.of(anchor));
        ResolvedRouteAnchors noRoute = new ResolvedRouteAnchors(
                VERSION, ResolvedRouteAnchors.Status.NO_ROUTE, Map.of());
        ResolvedRouteAnchors noMatches = new ResolvedRouteAnchors(
                VERSION, ResolvedRouteAnchors.Status.ROUTE, Map.of());

        assertThat(noRoute.hasRoute()).isFalse();
        assertThat(noMatches.hasRoute()).isTrue();
        for (Object value : List.of(anchor, catalog, noRoute, noMatches)) {
            assertThat(value.toString()).contains("private")
                    .doesNotContain(ANCHOR_ID.toString(), VERSION.toString(), "80", "13");
        }
    }
}
