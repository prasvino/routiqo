package com.routiqo.core.routing.domain;

public record RouteStep(String instruction, double distanceMetres, double durationSeconds,
        RouteRequest.Coordinate location) {
    public RouteStep {
        if (instruction == null || instruction.isEmpty() || instruction.length() > 500
                || whitespace(instruction.codePointAt(0))
                || whitespace(instruction.codePointBefore(instruction.length()))
                || instruction.codePoints().anyMatch(point -> Character.getType(point) == Character.CONTROL)
                || !Double.isFinite(distanceMetres) || distanceMetres < 0
                || !Double.isFinite(durationSeconds) || durationSeconds < 0 || location == null)
            throw new IllegalArgumentException("Invalid route step");
    }
    private static boolean whitespace(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point);
    }
    @Override public String toString() { return "RouteStep[private]"; }
}
