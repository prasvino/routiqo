package com.routiqo.core.routing.domain;

import java.util.List;

public record PlaceResults(List<PlaceMatch> places, String attribution) {
    public PlaceResults {
        places = List.copyOf(places);
        if (places.size() > 5 || places.stream().map(PlaceMatch::id).distinct().count() != places.size()
                || attribution == null || attribution.isBlank() || attribution.length() > 2048)
            throw new IllegalArgumentException("Invalid place results");
    }
    @Override public String toString() { return "PlaceResults[private]"; }
}
