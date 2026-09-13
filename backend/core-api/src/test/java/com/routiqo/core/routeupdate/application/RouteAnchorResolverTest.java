package com.routiqo.core.routeupdate.application;

import com.routiqo.core.routeupdate.domain.QuickSignalValue.Category;
import com.routiqo.core.routeupdate.domain.ResolvedRouteAnchors;
import com.routiqo.core.routeupdate.domain.RouteAnchor;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import com.routiqo.core.routing.application.RouteProvider;
import com.routiqo.core.routing.domain.RouteOption;
import com.routiqo.core.routing.domain.RouteRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteAnchorResolverTest {
    private static final double EARTH_RADIUS_METRES = 6_371_008.8;
    private static final UUID VERSION = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final RouteRequest.Coordinate ORIGIN = coordinate(-0.02, 0);
    private static final RouteRequest.Coordinate DESTINATION = coordinate(0.02, 0);
    private static final RouteRequest REQUEST = new RouteRequest(
            RouteRequest.Mode.DRIVING, ORIGIN, DESTINATION);

    @Test void validatesDependenciesInputAndAmbientTransactionsBeforeProviderWork() {
        var provider = new StubProvider(List.of());
        RouteAnchorCatalog catalog = catalog(anchor(1, coordinate(0, 0), Category.QUEUE));
        assertConflict(() -> new RouteAnchorResolver(null, catalog));
        assertConflict(() -> new RouteAnchorResolver(new MapboxProvider(), catalog));
        assertConflict(() -> new RouteAnchorResolver(provider, null));
        var resolver = new RouteAnchorResolver(provider, catalog);
        assertConflict(() -> resolver.resolve(null, 0));
        assertConflict(() -> resolver.resolve(REQUEST, -1));
        assertConflict(() -> resolver.resolve(REQUEST, 3));
        assertThat(provider.calls).hasValue(0);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertConflict(() -> resolver.resolve(REQUEST, 0));
        } finally {
            TransactionSynchronizationManager.clear();
        }
        assertThat(provider.calls).hasValue(0);
    }

    @Test void distinguishesNoRouteFromARouteWithNoEligibleAnchors() {
        RouteAnchorCatalog catalog = catalog(anchor(1, coordinate(1, 1), Category.QUEUE));
        ResolvedRouteAnchors noRoute = new RouteAnchorResolver(
                new StubProvider(List.of()), catalog).resolve(REQUEST, 2);
        ResolvedRouteAnchors noMatch = new RouteAnchorResolver(
                new StubProvider(List.of(route(ORIGIN, coordinate(0, 0), DESTINATION))), catalog)
                .resolve(REQUEST, 0);

        assertThat(noRoute.status()).isEqualTo(ResolvedRouteAnchors.Status.NO_ROUTE);
        assertThat(noRoute.anchors()).isEmpty();
        assertThat(noMatch.status()).isEqualTo(ResolvedRouteAnchors.Status.ROUTE);
        assertThat(noMatch.anchors()).isEmpty();
        assertThat(noRoute.catalogVersion()).isEqualTo(VERSION);
    }

    @Test void requiresTheSelectedAvailableAlternativeWithoutFallback() {
        RouteAnchor primary = anchor(1, coordinate(-0.005, 0), Category.QUEUE);
        RouteAnchor alternate = anchor(2, coordinate(0.005, 0), Category.TRAFFIC);
        var routes = List.of(
                route(ORIGIN, primary.location(), DESTINATION),
                route(ORIGIN, alternate.location(), DESTINATION));
        var resolver = new RouteAnchorResolver(new StubProvider(routes), catalog(primary, alternate));

        assertThat(resolver.resolve(REQUEST, 1).anchors()).containsOnlyKeys(alternate.anchorId());
        assertConflict(() -> resolver.resolve(REQUEST, 2));

        var four = new ArrayList<>(routes);
        four.add(routes.getFirst());
        four.add(routes.getFirst());
        assertConflict(() -> new RouteAnchorResolver(new StubProvider(four), catalog(primary))
                .resolve(REQUEST, 0));

        RouteProvider nullRoutes = new RouteProvider() {
            @Override public Identity identity() { return Identity.VALHALLA; }
            @Override public List<RouteOption> routes(RouteRequest request) { return null; }
        };
        assertConflict(() -> new RouteAnchorResolver(nullRoutes, catalog(primary)).resolve(REQUEST, 0));
        var withNull = new ArrayList<RouteOption>();
        withNull.add(null);
        assertConflict(() -> new RouteAnchorResolver(new StubProvider(withNull), catalog(primary))
                .resolve(REQUEST, 0));
    }

    @Test void matchesOnlyActualVerticesAtTheInclusiveHundredMetreBoundary() {
        RouteRequest.Coordinate vertex = coordinate(0, 0);
        RouteAnchor exact = anchor(1, north(vertex, 100), Category.QUEUE, Category.RESTROOM);
        RouteAnchor beyond = anchor(2, north(vertex, 100.001), Category.TRAFFIC);
        RouteAnchor onLongSegment = anchor(3, coordinate(-0.01, 0), Category.PARKING);
        var resolver = new RouteAnchorResolver(
                new StubProvider(List.of(route(ORIGIN, vertex, DESTINATION))),
                catalog(exact, beyond, onLongSegment));

        ResolvedRouteAnchors result = resolver.resolve(REQUEST, 0);

        assertThat(result.anchors()).containsOnlyKeys(exact.anchorId());
        assertThat(result.anchors().get(exact.anchorId()))
                .containsExactlyInAnyOrder(Category.QUEUE, Category.RESTROOM);
    }

    @Test void excludesRequestedAndGeometryEndpointsAtTheInclusiveKilometreBoundary() {
        RouteRequest.Coordinate anchorPoint = coordinate(0, 0);
        RouteAnchor anchor = anchor(1, anchorPoint, Category.QUEUE);

        RouteRequest exactRequest = new RouteRequest(RouteRequest.Mode.DRIVING,
                west(anchorPoint, 1_000), DESTINATION);
        var exact = new RouteAnchorResolver(
                new StubProvider(List.of(route(exactRequest.origin(), anchorPoint, exactRequest.destination()))),
                catalog(anchor));
        assertThat(exact.resolve(exactRequest, 0).anchors()).isEmpty();

        RouteRequest beyondRequest = new RouteRequest(RouteRequest.Mode.DRIVING,
                west(anchorPoint, 1_000.001), DESTINATION);
        var beyond = new RouteAnchorResolver(
                new StubProvider(List.of(route(beyondRequest.origin(), anchorPoint, beyondRequest.destination()))),
                catalog(anchor));
        assertThat(beyond.resolve(beyondRequest, 0).anchors()).containsOnlyKeys(anchor.anchorId());

        RouteRequest requestedFarAway = new RouteRequest(RouteRequest.Mode.DRIVING,
                coordinate(-0.05, 0), coordinate(0.05, 0));
        var geometryEndpoint = new RouteAnchorResolver(
                new StubProvider(List.of(route(anchorPoint, requestedFarAway.destination()))), catalog(anchor));
        assertThat(geometryEndpoint.resolve(requestedFarAway, 0).anchors()).isEmpty();
    }

    @Test void handlesDatelineAndPolarDistancesWithoutInferringSegments() {
        RouteRequest datelineRequest = new RouteRequest(RouteRequest.Mode.DRIVING,
                coordinate(179.97, 0), coordinate(-179.97, 0));
        RouteAnchor dateline = anchor(1, coordinate(-179.9999, 0), Category.TRAFFIC);
        RouteRequest.Coordinate datelineVertex = coordinate(179.9999, 0);
        var datelineResolver = new RouteAnchorResolver(
                new StubProvider(List.of(route(datelineRequest.origin(), datelineVertex,
                        datelineRequest.destination()))), catalog(dateline));
        assertThat(datelineResolver.resolve(datelineRequest, 0).anchors())
                .containsOnlyKeys(dateline.anchorId());

        RouteRequest polarRequest = new RouteRequest(RouteRequest.Mode.WALKING,
                coordinate(-20, 89.9), coordinate(20, 89.9));
        RouteAnchor polar = anchor(2, coordinate(0.2, 89.999), Category.RESTROOM);
        RouteRequest.Coordinate polarVertex = coordinate(0, 89.999);
        var polarResolver = new RouteAnchorResolver(
                new StubProvider(List.of(route(polarRequest.origin(), polarVertex,
                        polarRequest.destination()))), catalog(polar));
        assertThat(polarResolver.resolve(polarRequest, 0).anchors()).containsOnlyKeys(polar.anchorId());
    }

    @Test void deniesTheWholeResolutionAboveOneHundredTwentyEightMatches() {
        var anchors = new ArrayList<RouteAnchor>();
        for (int index = 1; index <= 129; index++) {
            anchors.add(anchor(index, coordinate(0, 0), Category.QUEUE));
        }
        var resolver = new RouteAnchorResolver(
                new StubProvider(List.of(route(ORIGIN, coordinate(0, 0), DESTINATION))),
                new RouteAnchorCatalog(VERSION, anchors));

        assertConflict(() -> resolver.resolve(REQUEST, 0));
    }

    @Test void propagatesProviderFailureWithoutFallbackAndRedactsConflicts() {
        var failing = new RouteProvider() {
            @Override public Identity identity() { return Identity.VALHALLA; }
            @Override public List<RouteOption> routes(RouteRequest request) {
                throw new IllegalStateException("Routing is temporarily unavailable");
            }
        };
        var resolver = new RouteAnchorResolver(failing,
                catalog(anchor(1, coordinate(0, 0), Category.QUEUE)));

        assertThatThrownBy(() -> resolver.resolve(REQUEST, 0))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Routing is temporarily unavailable");
        Throwable conflict = org.assertj.core.api.Assertions.catchThrowable(
                () -> resolver.resolve(REQUEST, 3));
        assertThat(conflict).isExactlyInstanceOf(RouteAnchorResolutionConflict.class)
                .hasMessage("Route anchor resolution rejected").hasNoCause();
        assertThat(conflict.toString()).doesNotContain(VERSION.toString(), "0.02", "QUEUE");
    }

    private static RouteAnchorCatalog catalog(RouteAnchor... anchors) {
        return new RouteAnchorCatalog(VERSION, List.of(anchors));
    }

    private static RouteAnchor anchor(long id, RouteRequest.Coordinate coordinate,
            Category... categories) {
        return new RouteAnchor(new UUID(1, id), coordinate, Set.of(categories));
    }

    private static RouteOption route(RouteRequest.Coordinate... geometry) {
        return new RouteOption(1_000, 60, List.of(geometry));
    }

    private static RouteRequest.Coordinate north(RouteRequest.Coordinate point, double metres) {
        return coordinate(point.longitude(), point.latitude() + Math.toDegrees(metres / EARTH_RADIUS_METRES));
    }

    private static RouteRequest.Coordinate west(RouteRequest.Coordinate point, double metres) {
        return coordinate(point.longitude() - Math.toDegrees(metres / EARTH_RADIUS_METRES), point.latitude());
    }

    private static RouteRequest.Coordinate coordinate(double longitude, double latitude) {
        return new RouteRequest.Coordinate(longitude, latitude);
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isExactlyInstanceOf(RouteAnchorResolutionConflict.class)
                .hasMessage("Route anchor resolution rejected").hasNoCause();
    }

    private static final class StubProvider implements RouteProvider {
        private final List<RouteOption> result;
        private final AtomicInteger calls = new AtomicInteger();

        private StubProvider(List<RouteOption> result) { this.result = result; }

        @Override public Identity identity() { return Identity.VALHALLA; }

        @Override public List<RouteOption> routes(RouteRequest request) {
            calls.incrementAndGet();
            return result;
        }
    }

    private static final class MapboxProvider implements RouteProvider {
        @Override public Identity identity() { return Identity.MAPBOX; }
        @Override public List<RouteOption> routes(RouteRequest request) { return List.of(); }
    }
}
