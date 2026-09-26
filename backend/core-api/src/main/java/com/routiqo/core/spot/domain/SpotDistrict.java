package com.routiqo.core.spot.domain;

import java.util.Set;

/**
 * Fixed district keys for the pilot corridors. Diwali 2026: Chennai to Tirunelveli, Thoothukudi and
 * Thanjavur. Pongal 2027 adds Chennai to Coimbatore via Vellore and Salem. Adding a key is a code
 * change, so a catalog cannot introduce districts the official-alert reader has not been reviewed for.
 */
public final class SpotDistrict {
    private static final Set<String> KEYS = Set.of(
            // Diwali 2026 corridors
            "chennai", "chengalpattu", "kanchipuram", "tiruvallur", "villupuram", "kallakurichi",
            "perambalur", "ariyalur", "tiruchirappalli", "pudukkottai", "thanjavur", "dindigul",
            "madurai", "virudhunagar", "tirunelveli", "thoothukudi",
            // Pongal 2027 addition: Chennai to Coimbatore
            "ranipet", "vellore", "tirupathur", "krishnagiri", "dharmapuri", "salem", "namakkal",
            "erode", "tiruppur", "coimbatore");

    private SpotDistrict() {}

    public static boolean known(String key) { return key != null && KEYS.contains(key); }

    public static Set<String> keys() { return KEYS; }
}
