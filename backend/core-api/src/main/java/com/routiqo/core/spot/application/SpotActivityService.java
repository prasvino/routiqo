package com.routiqo.core.spot.application;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.journey.application.ActiveJourneyReader;
import com.routiqo.core.spot.domain.Spot;
import com.routiqo.core.spot.domain.SpotActivity;
import com.routiqo.core.spot.domain.SpotCatalog;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Activity read for the Spots ahead (SPOTS_SPEC, ADR 0067). The requested Spot IDs are private journey
 * data: they are used only to shape this response and are never logged, stored or used as a rate key.
 * Posts and signals do not exist yet, so every known Spot is quiet.
 */
public final class SpotActivityService {
    static final String RATE_CATEGORY = "spot-activity-read-account";
    static final int RATE_LIMIT = 20;
    public static final int MAX_SPOTS = 20;
    private final AuthRateGate rates;
    private final ActiveJourneyReader journeys;
    private final SpotCatalog catalog;
    private final Map<UUID, Spot> spots;
    private final Clock clock;

    public SpotActivityService(AuthRateGate rates, ActiveJourneyReader journeys, SpotCatalog catalog,
            Clock clock) {
        this.rates = rates;
        this.journeys = journeys;
        this.catalog = catalog;
        this.spots = Map.copyOf(catalog.byId());
        this.clock = clock;
    }

    public SpotActivity read(UUID actor, List<UUID> spotIds) {
        if (actor == null || spotIds == null || spotIds.isEmpty() || spotIds.size() > MAX_SPOTS)
            throw new IllegalArgumentException("Invalid Spot activity request");
        final boolean allowed;
        try {
            allowed = rates.allow(actor.toString(), RATE_CATEGORY, RATE_LIMIT);
        } catch (RuntimeException unavailable) {
            throw new SpotsUnavailable();
        }
        if (!allowed) throw new SpotsRateLimited();
        final boolean active;
        try {
            active = journeys.hasActiveJourney(actor);
        } catch (RuntimeException unavailable) {
            throw new SpotsUnavailable();
        }
        if (!active) throw new SpotActivityNeedsActiveJourney();
        var entries = new ArrayList<SpotActivity.Entry>(spotIds.size());
        for (UUID id : spotIds)
            if (spots.containsKey(id)) entries.add(new SpotActivity.Entry(id, SpotActivity.State.QUIET));
        return new SpotActivity(clock.instant(), catalog.version(), entries);
    }
}
