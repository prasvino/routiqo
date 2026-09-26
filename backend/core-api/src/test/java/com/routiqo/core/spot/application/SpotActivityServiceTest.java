package com.routiqo.core.spot.application;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.routing.domain.RouteRequest;
import com.routiqo.core.spot.domain.Spot;
import com.routiqo.core.spot.domain.SpotActivity;
import com.routiqo.core.spot.domain.SpotCatalog;
import com.routiqo.core.spot.domain.SpotCategory;
import com.routiqo.core.spot.domain.SpotCorridor;
import com.routiqo.core.spot.domain.SpotKind;
import com.routiqo.core.spot.domain.SpotProvenance;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpotActivityServiceTest {
    private static final UUID VERSION = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
    private static final UUID FIRST = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID UNKNOWN = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-4000-8000-0000000000ff");
    private static final Instant NOW = Instant.parse("2026-11-05T06:30:00Z");

    private static Spot spot(UUID id) {
        return new Spot(id, "Test Toll", "சோதனை", SpotKind.TOLL, new RouteRequest.Coordinate(79.9, 12.7),
                "chengalpattu", List.of("gst-trunk"), Set.of(SpotCategory.TRAFFIC),
                new SpotProvenance("Curator", SpotProvenance.Source.OSM, LocalDate.of(2026, 10, 1)));
    }

    private static final SpotCatalog CATALOG = new SpotCatalog(VERSION,
            List.of(new SpotCorridor("gst-trunk", "GST Road")), List.of(spot(FIRST), spot(SECOND)));

    private final List<String> rateCalls = new ArrayList<>();
    private final AtomicBoolean allow = new AtomicBoolean(true);
    private final AtomicBoolean rateFails = new AtomicBoolean();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicBoolean journeyFails = new AtomicBoolean();

    private SpotActivityService service() {
        AuthRateGate rates = (key, category, limit) -> {
            rateCalls.add(key + "|" + category + "|" + limit);
            if (rateFails.get()) throw new IllegalStateException("rate store down");
            return allow.get();
        };
        return new SpotActivityService(rates, owner -> {
            if (journeyFails.get()) throw new IllegalStateException("journey store down");
            return active.get() && owner.equals(ACTOR);
        }, CATALOG, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void returnsQuietKnownSpotsInRequestOrderAndIgnoresUnknownIds() {
        SpotActivity activity = service().read(ACTOR, List.of(FIRST, SECOND, UNKNOWN));

        assertThat(activity.serverTime()).isEqualTo(NOW);
        assertThat(activity.catalogVersion()).isEqualTo(VERSION);
        assertThat(activity.spots()).extracting(SpotActivity.Entry::id).containsExactly(FIRST, SECOND);
        assertThat(activity.spots()).allSatisfy(entry ->
                assertThat(entry.state()).isEqualTo(SpotActivity.State.QUIET));
        assertThat(service().read(ACTOR, List.of(UNKNOWN)).spots()).isEmpty();
        assertThat(activity.toString()).doesNotContain(FIRST.toString());
    }

    @Test void rateKeyIsTheAccountNeverTheRequestedSpots() {
        service().read(ACTOR, List.of(FIRST));

        assertThat(rateCalls).containsExactly(ACTOR + "|spot-activity-read-account|20");
    }

    @Test void requiresAnActiveJourneyAfterTheRateGate() {
        active.set(false);

        assertThatThrownBy(() -> service().read(ACTOR, List.of(FIRST)))
                .isInstanceOf(SpotActivityNeedsActiveJourney.class);
        assertThat(rateCalls).hasSize(1);
    }

    @Test void mapsLimitsAndUnavailableAuthoritiesWithoutCheckingJourneysFirst() {
        allow.set(false);
        assertThatThrownBy(() -> service().read(ACTOR, List.of(FIRST))).isInstanceOf(SpotsRateLimited.class);
        allow.set(true);
        rateFails.set(true);
        assertThatThrownBy(() -> service().read(ACTOR, List.of(FIRST))).isInstanceOf(SpotsUnavailable.class);
        rateFails.set(false);
        journeyFails.set(true);
        assertThatThrownBy(() -> service().read(ACTOR, List.of(FIRST))).isInstanceOf(SpotsUnavailable.class);
    }

    @Test void rejectsEmptyOrOversizedRequests() {
        var tooMany = new ArrayList<UUID>();
        for (int index = 0; index < 21; index++) tooMany.add(UUID.randomUUID());
        assertThatThrownBy(() -> service().read(ACTOR, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().read(ACTOR, tooMany)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().read(null, List.of(FIRST))).isInstanceOf(IllegalArgumentException.class);
        assertThat(rateCalls).isEmpty();
    }

    @Test void catalogReadsUseTheirOwnAccountGate() {
        var calls = new ArrayList<String>();
        var catalog = new SpotCatalogService((key, category, limit) -> {
            calls.add(key + "|" + category + "|" + limit);
            return calls.size() == 1;
        });
        catalog.admitRead(ACTOR);
        assertThatThrownBy(() -> catalog.admitRead(ACTOR)).isInstanceOf(SpotsRateLimited.class);
        assertThat(calls.getFirst()).isEqualTo(ACTOR + "|spot-catalog-read-account|10");
        var broken = new SpotCatalogService((key, category, limit) -> { throw new IllegalStateException(); });
        assertThatThrownBy(() -> broken.admitRead(ACTOR)).isInstanceOf(SpotsUnavailable.class);
    }
}
