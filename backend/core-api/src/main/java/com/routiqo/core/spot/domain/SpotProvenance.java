package com.routiqo.core.spot.domain;

import java.time.LocalDate;
import java.util.Locale;

/** Curation record for a Spot. Server-only: never returned to clients. */
public record SpotProvenance(String curator, Source source, LocalDate reviewedAt) {
    public enum Source {
        OSM, FIELD_VISIT, OPERATOR_KNOWLEDGE, PUBLIC_LISTING;

        public static Source fromKey(String key) {
            for (Source source : values())
                if (source.name().toLowerCase(Locale.ROOT).equals(key)) return source;
            throw new IllegalArgumentException("Unknown Spot provenance source");
        }
    }

    public SpotProvenance {
        if (!SpotLabel.valid(curator) || source == null || reviewedAt == null)
            throw new IllegalArgumentException("Invalid Spot provenance");
    }

    @Override public String toString() { return "SpotProvenance[private]"; }
}
