package com.routiqo.core.routing.domain;

import java.util.List;

public record RouteOption(double distanceMetres, double durationSeconds, List<RouteRequest.Coordinate> geometry,
        List<RouteStep> steps) {
    public RouteOption(double distanceMetres, double durationSeconds, List<RouteRequest.Coordinate> geometry) {
        this(distanceMetres, durationSeconds, geometry, List.of());
    }
    public RouteOption {
        if (!Double.isFinite(distanceMetres) || !Double.isFinite(durationSeconds)
                || distanceMetres < 0 || durationSeconds < 0 || geometry == null
                || geometry.size() < 2 || geometry.size() > 10000 || steps == null || steps.size() > 500)
            throw new IllegalArgumentException("Invalid route result");
        geometry = List.copyOf(geometry);
        steps = List.copyOf(steps);
    }
}
