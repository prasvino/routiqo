package com.routiqo.core.routing;

import com.routiqo.core.routing.application.RegionLimitedRouteProvider;
import com.routiqo.core.routing.application.RouteProvider;
import com.routiqo.core.routing.domain.RouteOption;
import com.routiqo.core.routing.domain.RouteOutsideCoverageException;
import com.routiqo.core.routing.domain.RouteRequest;
import com.routiqo.core.routing.domain.RouteStep;
import com.routiqo.core.routing.domain.RoutingRegion;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class RegionLimitedRouteProviderTest {
    private static final RoutingRegion REGION = new RoutingRegion(70, 10, 85, 20);
    private static final RouteRequest REQUEST = request(coordinate(71, 11), coordinate(84, 19));

    @Test void rejectsInvalidOrNonRegionalBoundsWithoutDisclosingThem() {
        for (double[] bounds : List.of(
                new double[] {Double.NaN, 10, 85, 20},
                new double[] {70, Double.NEGATIVE_INFINITY, 85, 20},
                new double[] {70, 10, Double.POSITIVE_INFINITY, 20},
                new double[] {70, 10, 85, Double.NaN},
                new double[] {-181, 10, 85, 20},
                new double[] {70, -91, 85, 20},
                new double[] {70, 10, 181, 20},
                new double[] {70, 10, 85, 91},
                new double[] {70, 10, 70, 20},
                new double[] {71, 10, 70, 20},
                new double[] {70, 10, 85, 10},
                new double[] {70, 11, 85, 10},
                new double[] {-90, 10, 90, 20},
                new double[] {-100, 10, 100, 20})) {
            Throwable failure = catchThrowable(
                    () -> new RoutingRegion(bounds[0], bounds[1], bounds[2], bounds[3]));
            assertThat(failure)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid routing region")
                    .hasNoCause();
            assertThat(failure.toString())
                    .doesNotContain(Double.toString(bounds[0]), Double.toString(bounds[1]));
        }
    }

    @Test void containsEveryBoundaryInclusivelyAndRejectsPointsBeyondIt() {
        for (RouteRequest.Coordinate boundary : List.of(
                coordinate(70, 10), coordinate(70, 20), coordinate(85, 10), coordinate(85, 20),
                coordinate(77.5, 10), coordinate(77.5, 20), coordinate(70, 15), coordinate(85, 15)))
            assertThat(REGION.contains(boundary)).isTrue();

        for (RouteRequest.Coordinate outside : List.of(
                coordinate(69.999, 15), coordinate(85.001, 15),
                coordinate(75, 9.999), coordinate(75, 20.001)))
            assertThat(REGION.contains(outside)).isFalse();
        assertThatThrownBy(() -> REGION.contains(null)).isInstanceOf(NullPointerException.class);
    }

    @Test void rejectsNullDependenciesAndRequestsBeforeCallingTheDelegate() {
        var delegate = new StubProvider(RouteProvider.Identity.VALHALLA, List.of());
        assertThatThrownBy(() -> new RegionLimitedRouteProvider(null, REGION))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RegionLimitedRouteProvider(delegate, null))
                .isInstanceOf(NullPointerException.class);

        var provider = new RegionLimitedRouteProvider(delegate, REGION);
        assertThatThrownBy(() -> provider.routes(null)).isInstanceOf(NullPointerException.class);
        assertThat(delegate.calls).hasValue(0);
    }

    @Test void rejectsEitherOutsideEndpointBeforeCallingTheDelegate() {
        var delegate = new StubProvider(RouteProvider.Identity.VALHALLA, List.of());
        var provider = new RegionLimitedRouteProvider(delegate, REGION);

        for (RouteRequest request : List.of(
                request(coordinate(69, 15), coordinate(84, 19)),
                request(coordinate(71, 11), coordinate(86, 15))))
            assertCoverageFailure(() -> provider.routes(request));

        assertThat(delegate.calls).hasValue(0);
    }

    @Test void preservesDelegateIdentityAndNoRouteAsAnImmutableResult() {
        var mutableNoRoute = new ArrayList<RouteOption>();
        var delegate = new StubProvider(RouteProvider.Identity.VALHALLA, mutableNoRoute);
        var provider = new RegionLimitedRouteProvider(delegate, REGION);

        assertThat(provider.identity()).isEqualTo(RouteProvider.Identity.VALHALLA);
        List<RouteOption> result = provider.routes(REQUEST);

        assertThat(result).isEmpty();
        assertThatThrownBy(() -> result.add(route(REQUEST.origin(), REQUEST.destination())))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(mutableNoRoute).isEmpty();
        assertThat(delegate.calls).hasValue(1);
    }

    @Test void returnsTheSameRouteObjectsWithoutMutatingTheDelegateList() {
        RouteOption route = route(REQUEST.origin(), REQUEST.destination());
        var delegated = new ArrayList<>(List.of(route));
        var provider = new RegionLimitedRouteProvider(
                new StubProvider(RouteProvider.Identity.MAPBOX, delegated), REGION);

        List<RouteOption> result = provider.routes(REQUEST);

        assertThat(result).containsExactly(route);
        assertThat(result.getFirst()).isSameAs(route);
        assertThat(delegated).containsExactly(route);
        assertThatThrownBy(() -> result.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void rejectsOutOfRegionPrimaryOrAlternativeGeometryAsOneFailure() {
        RouteOption covered = route(REQUEST.origin(), REQUEST.destination());
        RouteOption outside = new RouteOption(100, 60,
                List.of(REQUEST.origin(), coordinate(86, 15), REQUEST.destination()));

        for (List<RouteOption> routes : List.of(List.of(outside), List.of(covered, outside))) {
            var provider = new RegionLimitedRouteProvider(
                    new StubProvider(RouteProvider.Identity.VALHALLA, routes), REGION);
            assertCoverageFailure(() -> provider.routes(REQUEST));
        }
    }

    @Test void rejectsOutOfRegionStepLocationsEvenWhenGeometryIsCovered() {
        RouteOption route = new RouteOption(100, 60,
                List.of(REQUEST.origin(), REQUEST.destination()),
                List.of(new RouteStep("Continue", 100, 60, coordinate(86, 15))));
        var provider = new RegionLimitedRouteProvider(
                new StubProvider(RouteProvider.Identity.VALHALLA, List.of(route)), REGION);

        assertCoverageFailure(() -> provider.routes(REQUEST));
    }

    @Test void regionAndCoverageFailuresRedactCoordinates() {
        assertThat(REGION.toString()).isEqualTo("RoutingRegion[private]")
                .doesNotContain("70", "85", "10", "20");
        Throwable failure = catchThrowable(() -> new RegionLimitedRouteProvider(
                new StubProvider(RouteProvider.Identity.VALHALLA,
                        List.of(route(REQUEST.origin(), coordinate(86, 15)))), REGION).routes(REQUEST));
        assertThat(failure)
                .isExactlyInstanceOf(RouteOutsideCoverageException.class)
                .hasMessage("Route is outside the configured coverage region")
                .hasNoCause();
        assertThat(failure.toString()).doesNotContain("86", "15");
    }

    private static void assertCoverageFailure(Runnable request) {
        assertThatThrownBy(request::run)
                .isExactlyInstanceOf(RouteOutsideCoverageException.class)
                .hasMessage("Route is outside the configured coverage region")
                .hasNoCause();
    }

    private static RouteRequest request(RouteRequest.Coordinate origin,
            RouteRequest.Coordinate destination) {
        return new RouteRequest(RouteRequest.Mode.DRIVING, origin, destination);
    }

    private static RouteRequest.Coordinate coordinate(double longitude, double latitude) {
        return new RouteRequest.Coordinate(longitude, latitude);
    }

    private static RouteOption route(RouteRequest.Coordinate start, RouteRequest.Coordinate end) {
        return new RouteOption(100, 60, List.of(start, end));
    }

    private static final class StubProvider implements RouteProvider {
        private final Identity identity;
        private final List<RouteOption> routes;
        private final AtomicInteger calls = new AtomicInteger();

        private StubProvider(Identity identity, List<RouteOption> routes) {
            this.identity = identity;
            this.routes = routes;
        }

        @Override public Identity identity() { return identity; }

        @Override public List<RouteOption> routes(RouteRequest request) {
            calls.incrementAndGet();
            return routes;
        }
    }
}
