package com.routiqo.core.spot.domain;

import java.util.regex.Pattern;

/** A pilot corridor declared in the catalog, e.g. {@code gst-trunk}. */
public record SpotCorridor(String id, String name) {
    private static final Pattern ID = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    public SpotCorridor {
        if (!validId(id) || !SpotLabel.valid(name)) throw new IllegalArgumentException("Invalid Spot corridor");
    }

    public static boolean validId(String id) {
        return id != null && id.length() <= 40 && ID.matcher(id).matches();
    }
}
