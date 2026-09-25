package com.routiqo.core.security;

/**
 * Fail-closed feature switch parsing (ADR 0065). Only the exact lowercase value {@code true} enables a
 * gated capability; a missing, empty, mixed-case, numeric, padded or otherwise ambiguous value leaves it
 * off. Spring's {@code havingValue} matching ignores case and {@code boolean} binding accepts
 * {@code 1/yes/on}, so gates for public or moderation capabilities use this instead.
 */
public final class FeatureFlags {
    private FeatureFlags() {}

    public static boolean enabled(String raw) {
        return "true".equals(raw);
    }
}
