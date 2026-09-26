package com.routiqo.core.spot.application;

import com.routiqo.core.identity.application.AuthRateGate;
import java.util.UUID;

/** Admits a catalog read for an authenticated account under its own rate gate. */
public final class SpotCatalogService {
    static final String RATE_CATEGORY = "spot-catalog-read-account";
    static final int RATE_LIMIT = 10;
    private final AuthRateGate rates;

    public SpotCatalogService(AuthRateGate rates) { this.rates = rates; }

    public void admitRead(UUID actor) {
        if (actor == null) throw new IllegalArgumentException("Actor required");
        final boolean allowed;
        try {
            allowed = rates.allow(actor.toString(), RATE_CATEGORY, RATE_LIMIT);
        } catch (RuntimeException unavailable) {
            throw new SpotsUnavailable();
        }
        if (!allowed) throw new SpotsRateLimited();
    }
}
