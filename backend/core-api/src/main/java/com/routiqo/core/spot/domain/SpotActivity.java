package com.routiqo.core.spot.domain;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Activity for the requested Spots that exist in the current catalog. Until posts and signals exist,
 * every Spot is {@link State#QUIET}; the requested IDs are never retained.
 */
public record SpotActivity(Instant serverTime, UUID catalogVersion, List<Entry> spots) {
    public enum State {
        LIVE, FADING, QUIET;

        public String key() { return name().toLowerCase(Locale.ROOT); }
    }

    public record Entry(UUID id, State state) {
        public Entry {
            if (id == null || state == null) throw new IllegalArgumentException("Invalid Spot activity");
        }
        @Override public String toString() { return "SpotActivityEntry[private]"; }
    }

    public SpotActivity {
        if (serverTime == null || catalogVersion == null || spots == null || spots.size() > 20
                || spots.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Invalid Spot activity");
        spots = List.copyOf(spots);
    }

    @Override public String toString() { return "SpotActivity[private]"; }
}
