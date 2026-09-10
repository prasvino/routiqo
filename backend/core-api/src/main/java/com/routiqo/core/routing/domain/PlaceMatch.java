package com.routiqo.core.routing.domain;

public record PlaceMatch(String id, String label, RouteRequest.Coordinate coordinate) {
    public PlaceMatch {
        if (id == null || id.isBlank() || id.length() > 512 || label == null || label.isBlank()
                || label.length() > 512 || coordinate == null
                || id.codePoints().anyMatch(Character::isISOControl)
                || label.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid place result");
    }
    @Override public String toString() { return "PlaceMatch[private]"; }
}
