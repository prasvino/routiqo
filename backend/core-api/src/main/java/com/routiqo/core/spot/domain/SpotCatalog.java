package com.routiqo.core.spot.domain;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One immutable curated catalog version ({@code routiqo-spots/1}). */
public record SpotCatalog(UUID version, List<SpotCorridor> corridors, List<Spot> spots) {
    public static final int MAX_SPOTS = 512;
    public static final int MAX_CORRIDORS = 16;
    private static final UUID NIL_ID = new UUID(0, 0);

    public SpotCatalog {
        if (version == null || NIL_ID.equals(version) || corridors == null || spots == null
                || corridors.isEmpty() || corridors.size() > MAX_CORRIDORS
                || spots.isEmpty() || spots.size() > MAX_SPOTS
                || corridors.stream().anyMatch(Objects::isNull) || spots.stream().anyMatch(Objects::isNull)) {
            throw invalid();
        }
        corridors = List.copyOf(corridors);
        spots = List.copyOf(spots);
        var corridorIds = new HashSet<String>();
        for (SpotCorridor corridor : corridors) if (!corridorIds.add(corridor.id())) throw invalid();
        var spotIds = new HashSet<UUID>();
        for (Spot spot : spots) {
            if (!spotIds.add(spot.id()) || !corridorIds.containsAll(spot.corridors())) throw invalid();
        }
    }

    public Map<UUID, Spot> byId() {
        var index = new LinkedHashMap<UUID, Spot>();
        for (Spot spot : spots) index.put(spot.id(), spot);
        return index;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Spot catalog");
    }

    @Override public String toString() { return "SpotCatalog[private]"; }
}
