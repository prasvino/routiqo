package com.routiqo.core.routing.domain;

public record RouteRequest(Mode mode, Coordinate origin, Coordinate destination) {
    public enum Mode { DRIVING, WALKING, CYCLING }
    public record Coordinate(double longitude, double latitude) {
        public Coordinate {
            if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                    || Math.abs(longitude) > 180 || Math.abs(latitude) > 90)
                throw new IllegalArgumentException("Invalid route coordinate");
        }
        @Override public String toString() { return "Coordinate[private]"; }
    }
    public RouteRequest {
        if (mode == null || origin == null || destination == null || origin.equals(destination))
            throw new IllegalArgumentException("Choose a mode and two different endpoints");
    }
}
