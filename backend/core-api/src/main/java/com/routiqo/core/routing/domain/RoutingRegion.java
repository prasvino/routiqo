package com.routiqo.core.routing.domain;

import java.util.Objects;

public record RoutingRegion(double minLongitude, double minLatitude, double maxLongitude, double maxLatitude) {
    public RoutingRegion {
        if (!Double.isFinite(minLongitude) || !Double.isFinite(minLatitude)
                || !Double.isFinite(maxLongitude) || !Double.isFinite(maxLatitude)
                || minLongitude < -180 || maxLongitude > 180
                || minLatitude < -90 || maxLatitude > 90
                || minLongitude >= maxLongitude || minLatitude >= maxLatitude
                || maxLongitude - minLongitude >= 180)
            throw new IllegalArgumentException("Invalid routing region");
    }

    public boolean contains(RouteRequest.Coordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate");
        return coordinate.longitude() >= minLongitude && coordinate.longitude() <= maxLongitude
                && coordinate.latitude() >= minLatitude && coordinate.latitude() <= maxLatitude;
    }

    @Override public String toString() { return "RoutingRegion[private]"; }
}
