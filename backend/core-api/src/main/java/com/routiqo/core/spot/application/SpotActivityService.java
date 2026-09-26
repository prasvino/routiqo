package com.routiqo.core.spot.application;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.journey.application.ActiveJourneyReader;
import com.routiqo.core.moderation.application.BlockedAccountsReader;
import com.routiqo.core.spot.domain.Spot;
import com.routiqo.core.spot.domain.SpotActivity;
import com.routiqo.core.spot.domain.SpotCatalog;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Activity read for the Spots ahead (SPOTS_SPEC, ADR 0067). The requested Spot IDs are private journey
 * data: they are used only to shape this response and are never logged, stored or used as a rate key.
 * Content (signal summaries, posts, highlights) comes from {@link SpotActivityReader}; without any,
 * every known Spot is quiet. Posts and votes by accounts the viewer has blocked are left out for that
 * viewer only (ADR 0072); signal summaries are unattributed and stay.
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
    private final SpotActivityReader content;
    private final BlockedAccountsReader blocks;

    public SpotActivityService(AuthRateGate rates, ActiveJourneyReader journeys, SpotCatalog catalog,
            Clock clock) {
        this(rates, journeys, catalog, clock,
                (ids, now, hidden) -> new SpotActivityReader.Contents(List.of(), List.of(), List.of(), List.of()));
    }

    public SpotActivityService(AuthRateGate rates, ActiveJourneyReader journeys, SpotCatalog catalog,
            Clock clock, SpotActivityReader content) {
        this(rates, journeys, catalog, clock, content, viewer -> Set.of());
    }

    public SpotActivityService(AuthRateGate rates, ActiveJourneyReader journeys, SpotCatalog catalog,
            Clock clock, SpotActivityReader content, BlockedAccountsReader blocks) {
        this.content = java.util.Objects.requireNonNull(content);
        this.blocks = java.util.Objects.requireNonNull(blocks);
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
        List<UUID> known = spotIds.stream().filter(spots::containsKey).toList();
        var now = clock.instant();
        final SpotActivityReader.Contents rows;
        try {
            rows = known.isEmpty()
                    ? new SpotActivityReader.Contents(List.of(), List.of(), List.of(), List.of())
                    : content.read(known, now, blocks.blockedBy(actor));
        } catch (RuntimeException unavailable) {
            throw new SpotsUnavailable();
        }
        var entries = new ArrayList<SpotActivity.Entry>(known.size());
        for (UUID id : known) entries.add(SpotActivityAssembler.entry(id, actor, rows, now));
        return new SpotActivity(now, catalog.version(), entries);
    }
}
