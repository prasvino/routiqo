package com.routiqo.core.spot.domain;

import com.routiqo.core.routing.domain.RouteRequest;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** A curated public place on a pilot corridor. Reference data, never evidence of anyone's presence. */
public record Spot(UUID id, String name, String nameTa, SpotKind kind, RouteRequest.Coordinate location,
        String district, List<String> corridors, Set<SpotCategory> categories, SpotProvenance provenance) {
    private static final UUID NIL_ID = new UUID(0, 0);

    public Spot {
        if (id == null || NIL_ID.equals(id) || !SpotLabel.valid(name) || !SpotLabel.valid(nameTa)
                || kind == null || location == null || !SpotDistrict.known(district)
                || corridors == null || corridors.isEmpty() || corridors.size() > 16
                || corridors.stream().anyMatch(corridor -> !SpotCorridor.validId(corridor))
                || Set.copyOf(corridors).size() != corridors.size()
                || categories == null || categories.isEmpty()
                || categories.stream().anyMatch(Objects::isNull) || provenance == null) {
            throw new IllegalArgumentException("Invalid Spot");
        }
        corridors = List.copyOf(corridors);
        categories = Set.copyOf(categories);
    }
}
