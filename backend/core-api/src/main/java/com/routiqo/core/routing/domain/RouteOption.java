package com.routiqo.core.routing.domain;

import java.util.List;

public record RouteOption(double distanceMetres, double durationSeconds, List<RouteRequest.Coordinate> geometry) {
    public RouteOption {
        if (!Double.isFinite(distanceMetres) || !Double.isFinite(durationSeconds)
                || distanceMetres < 0 || durationSeconds < 0 || geometry == null
                || geometry.size() < 2 || geometry.size() > 10000)
            throw new IllegalArgumentException("Invalid route result");
        geometry = List.copyOf(geometry);
    }
}
