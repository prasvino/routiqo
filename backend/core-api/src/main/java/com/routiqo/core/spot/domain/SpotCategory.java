package com.routiqo.core.spot.domain;

import java.util.Locale;

/** One-tap signal categories a Spot allows (POSTS_AND_SIGNALS_SPEC); the wire key is lowercase. */
public enum SpotCategory {
    TRAFFIC, QUEUE, FOOD, FUEL, RESTROOM;

    public String key() { return name().toLowerCase(Locale.ROOT); }

    public static SpotCategory fromKey(String key) {
        for (SpotCategory category : values()) if (category.key().equals(key)) return category;
        throw new IllegalArgumentException("Unknown Spot category");
    }
}
