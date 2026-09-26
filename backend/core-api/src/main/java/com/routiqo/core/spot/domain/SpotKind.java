package com.routiqo.core.spot.domain;

import java.util.Locale;

/** Curated place kinds on a pilot corridor; the wire key is the lowercase name. */
public enum SpotKind {
    TOLL, EATERY, FUEL, RESTROOM, BUS_STAND, TEMPLE, JUNCTION, REST_AREA;

    public String key() { return name().toLowerCase(Locale.ROOT); }

    public static SpotKind fromKey(String key) {
        for (SpotKind kind : values()) if (kind.key().equals(key)) return kind;
        throw new IllegalArgumentException("Unknown Spot kind");
    }
}
