package com.routiqo.core.routing.application;

import com.routiqo.core.routing.domain.RouteOption;
import com.routiqo.core.routing.domain.RouteOutsideCoverageException;
import com.routiqo.core.routing.domain.RouteRequest;
import com.routiqo.core.routing.domain.RoutingRegion;
import java.util.List;
import java.util.Objects;

public final class RegionLimitedRouteProvider implements RouteProvider {
    private final RouteProvider delegate;
    private final RoutingRegion region;

    public RegionLimitedRouteProvider(RouteProvider delegate, RoutingRegion region) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.region = Objects.requireNonNull(region, "region");
    }

    @Override public Identity identity() { return delegate.identity(); }

    @Override public List<RouteOption> routes(RouteRequest request) {
        Objects.requireNonNull(request, "request");
        requireCovered(request.origin());
        requireCovered(request.destination());

        List<RouteOption> routes = List.copyOf(
                Objects.requireNonNull(delegate.routes(request), "delegate routes"));
        for (RouteOption route : routes) {
            route.geometry().forEach(this::requireCovered);
            route.steps().forEach(step -> requireCovered(step.location()));
        }
        return routes;
    }

    private void requireCovered(RouteRequest.Coordinate coordinate) {
        if (!region.contains(coordinate)) throw new RouteOutsideCoverageException();
    }
}
