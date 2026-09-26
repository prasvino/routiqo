package com.routiqo.core.spot.domain;

import java.util.Locale;

/** A post's type sets its life: traffic or incident (short), or a place tip (a day). */
public enum PostType {
    TRAFFIC, PLACE;

    public String key() { return name().toLowerCase(Locale.ROOT); }

    public static PostType fromKey(String key) {
        for (PostType type : values()) if (type.key().equals(key)) return type;
        throw new IllegalArgumentException("Unknown post type");
    }
}
