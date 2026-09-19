package com.routiqo.core.routeupdate.application;

import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.moderation.application.ContributionRestrictionReader;
import com.routiqo.core.moderation.domain.ContributorAssessment;
import com.routiqo.core.privacy.application.PresenceConsentParticipant;
import com.routiqo.core.privacy.domain.PresenceConsent;
import com.routiqo.core.routeupdate.domain.LiveRouteContext;
import com.routiqo.core.routeupdate.domain.PrivateAnchorChoice;
import com.routiqo.core.routeupdate.domain.PrivateAnchorChoiceSnapshot;
import com.routiqo.core.routeupdate.domain.QuickSignalValue.Category;
import com.routiqo.core.routeupdate.domain.RouteAnchor;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import com.routiqo.core.routeupdate.domain.StoredLiveRouteContext;
import com.routiqo.core.routing.domain.RouteRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivateAnchorChoiceServiceTest {
    private static final UUID ACTOR = uuid(1);
    private static final UUID JOURNEY = uuid(2);
    private static final UUID CONTEXT = uuid(3);
    private static final UUID CATALOG = uuid(4);
    private static final UUID HIGH = UUID.fromString("f0000000-0000-4000-8000-000000000005");
    private static final UUID LOW = UUID.fromString("10000000-0000-4000-8000-000000000006");
    private static final Instant ISSUED = Instant.parse("2026-09-19T12:00:00Z");
    private static final Instant EXPIRES = ISSUED.plusSeconds(900);

    @Test void returnsOnlyTheExactCanonicalImmutableLabeledSubset() {
        Fixture fixture = new Fixture();
        PrivateAnchorChoiceSnapshot result = fixture.service().read(ACTOR, JOURNEY);

        assertThat(fixture.authority.calls).isEqualTo(1);
        assertThat(fixture.order).containsExactly("consent", "context", "restriction", "clock");
        assertThat(result.contextId()).isEqualTo(CONTEXT);
        assertThat(result.revision()).isEqualTo(11);
        assertThat(result.consentGeneration()).isEqualTo(7);
        assertThat(result.issuedAt()).isEqualTo(ISSUED);
        assertThat(result.expiresAt()).isEqualTo(EXPIRES);
        assertThat(result.choices()).extracting(PrivateAnchorChoice::anchorId)
                .containsExactly(LOW, HIGH);
        assertThat(result.choices()).extracting(PrivateAnchorChoice::displayLabel)
                .containsExactly("Low area", "High area");
        assertThat(result.choices().getFirst().categories()).containsExactly(Category.QUEUE);
        assertThatThrownBy(() -> result.choices().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.choices().getFirst().categories().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        for (Object value : List.of(result, result.choices().getFirst(), fixture.service())) {
            assertThat(value.toString()).contains("private").doesNotContain(
                    ACTOR.toString(), JOURNEY.toString(), CONTEXT.toString(), "Low area");
        }
    }

    @Test void deniesInvalidOrMismatchedJourneyAndConsentGenerically() {
        assertUnavailable(new Fixture(), null, JOURNEY);
        assertUnavailable(new Fixture(), new UUID(0, 0), JOURNEY);
        assertUnavailable(new Fixture(), ACTOR, null);
        Fixture inactive = new Fixture();
        inactive.authority.journey = inactive.authority.journey.complete(ACTOR, ISSUED);
        assertUnavailable(inactive, ACTOR, JOURNEY);
        Fixture foreignJourney = new Fixture();
        foreignJourney.authority.journey = Journey.start(uuid(20), ACTOR, Journey.Kind.TRIP, ISSUED);
        assertUnavailable(foreignJourney, ACTOR, JOURNEY);
        Fixture foreignOwner = new Fixture();
        foreignOwner.authority.journey = Journey.start(JOURNEY, uuid(21), Journey.Kind.TRIP, ISSUED);
        assertUnavailable(foreignOwner, ACTOR, JOURNEY);
        Fixture missingJourney = new Fixture();
        missingJourney.authority.journey = null;
        assertUnavailable(missingJourney, ACTOR, JOURNEY);
        Fixture missingConsent = new Fixture();
        missingConsent.consent = null;
        assertUnavailable(missingConsent, ACTOR, JOURNEY);
        for (PresenceConsent consent : List.of(
                new PresenceConsent(ACTOR, JOURNEY, 7, false, true),
                new PresenceConsent(ACTOR, JOURNEY, 7, false, false),
                new PresenceConsent(uuid(22), JOURNEY, 7, true, true),
                new PresenceConsent(ACTOR, uuid(23), 7, true, true))) {
            Fixture fixture = new Fixture();
            fixture.consent = consent;
            assertUnavailable(fixture, ACTOR, JOURNEY);
        }
    }

    @Test void deniesNoncurrentForeignOrUnprovenancedContextsAndIncompleteCatalogs() {
        List<StoredLiveRouteContext> invalid = List.of(
                stored(new LiveRouteContext(CONTEXT, uuid(30), JOURNEY, 11, Set.of(LOW)),
                        ISSUED, EXPIRES, Optional.of(CATALOG)),
                stored(new LiveRouteContext(CONTEXT, ACTOR, uuid(31), 11, Set.of(LOW)),
                        ISSUED, EXPIRES, Optional.of(CATALOG)),
                stored(context(Set.of(LOW)), ISSUED.plusSeconds(301), EXPIRES, Optional.of(CATALOG)),
                stored(context(Set.of(LOW)), ISSUED.minusSeconds(900), ISSUED.plusSeconds(300),
                        Optional.of(CATALOG)),
                stored(context(Set.of(LOW)), ISSUED, EXPIRES, Optional.empty()),
                stored(context(Set.of(LOW)), ISSUED, EXPIRES, Optional.of(uuid(32))));
        for (StoredLiveRouteContext stored : invalid) {
            Fixture fixture = new Fixture();
            fixture.stored = Optional.of(stored);
            assertUnavailable(fixture, ACTOR, JOURNEY);
        }
        Fixture absent = new Fixture();
        absent.stored = Optional.empty();
        assertUnavailable(absent, ACTOR, JOURNEY);
        Fixture missing = new Fixture();
        missing.stored = Optional.of(stored(context(Set.of(uuid(33))), ISSUED, EXPIRES,
                Optional.of(CATALOG)));
        assertUnavailable(missing, ACTOR, JOURNEY);
        Fixture unlabeled = new Fixture();
        unlabeled.catalog = new RouteAnchorCatalog(CATALOG, List.of(
                new RouteAnchor(LOW, point(), Set.of(Category.QUEUE)), labeled(HIGH, "High area")));
        assertUnavailable(unlabeled, ACTOR, JOURNEY);
    }

    @Test void deniesMissingForeignOrSuspendedRestrictionsAndSamplesTimeAfterAllReads() {
        Fixture missing = new Fixture();
        missing.restriction = null;
        assertUnavailable(missing, ACTOR, JOURNEY);
        Fixture foreign = new Fixture();
        foreign.restriction = ContributorAssessment.initial(uuid(40));
        assertUnavailable(foreign, ACTOR, JOURNEY);
        Fixture suspended = new Fixture();
        suspended.restriction = ContributorAssessment.initial(ACTOR).suspend(0);
        assertUnavailable(suspended, ACTOR, JOURNEY);

        Fixture expiresDuringReads = new Fixture();
        expiresDuringReads.expireDuringRestrictionRead = true;
        assertUnavailable(expiresDuringReads, ACTOR, JOURNEY);
        assertThat(expiresDuringReads.order)
                .containsExactly("consent", "context", "restriction", "clock");

        Fixture nanosecondExpiry = new Fixture();
        Instant issued = Instant.parse("2026-09-19T12:00:00.000000000Z");
        Instant expires = Instant.parse("2026-09-19T12:00:00.000000500Z");
        nanosecondExpiry.stored = Optional.of(stored(context(Set.of(LOW)), issued, expires,
                Optional.of(CATALOG)));
        nanosecondExpiry.clock.now = Instant.parse("2026-09-19T12:00:00.000000999Z");
        assertUnavailable(nanosecondExpiry, ACTOR, JOURNEY);
    }

    @Test void snapshotModelsDefensivelyRejectMalformedValuesAndRedactDiagnostics() {
        var choice = new PrivateAnchorChoice(LOW, "Low area", Set.of(Category.QUEUE));
        assertThatThrownBy(() -> new PrivateAnchorChoice(null, "Private label", Set.of(Category.QUEUE)))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> new PrivateAnchorChoice(LOW, "bad\u202Elabel", Set.of(Category.QUEUE)))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> new PrivateAnchorChoice(LOW, "Private label", Set.of()))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> new PrivateAnchorChoiceSnapshot(CONTEXT, 0, 0, ISSUED, EXPIRES,
                List.of(choice, choice))).isExactlyInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> new PrivateAnchorChoiceSnapshot(CONTEXT, 0, 0, ISSUED, EXPIRES,
                List.of())).isExactlyInstanceOf(IllegalArgumentException.class).hasNoCause();
        var tooMany = new ArrayList<PrivateAnchorChoice>();
        for (int index = 1; index <= 129; index++) {
            tooMany.add(new PrivateAnchorChoice(new UUID(1, index), "Area " + index,
                    Set.of(Category.QUEUE)));
        }
        assertThatThrownBy(() -> new PrivateAnchorChoiceSnapshot(CONTEXT, 0, 0, ISSUED, EXPIRES,
                tooMany)).isExactlyInstanceOf(IllegalArgumentException.class).hasNoCause();
        var highChoice = new PrivateAnchorChoice(HIGH, "High area", Set.of(Category.QUEUE));
        var lowChoice = new PrivateAnchorChoice(LOW, "Low area", Set.of(Category.QUEUE));
        assertThatThrownBy(() -> new PrivateAnchorChoiceSnapshot(CONTEXT, 0, 0, ISSUED, EXPIRES,
                List.of(highChoice, lowChoice)))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> new PrivateAnchorChoiceSnapshot(CONTEXT, -1, 0, ISSUED, EXPIRES,
                List.of(choice))).isExactlyInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> new PrivateAnchorChoiceSnapshot(CONTEXT, 0, -1, ISSUED, EXPIRES,
                List.of(choice))).isExactlyInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> new PrivateAnchorChoiceSnapshot(CONTEXT, 0, 0,
                Instant.MIN, EXPIRES, List.of(choice)))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> new PrivateAnchorChoiceSnapshot(CONTEXT, 0, 0,
                ISSUED, Instant.MAX, List.of(choice)))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> new PrivateAnchorChoiceSnapshot(CONTEXT, 0, 0,
                ISSUED, ISSUED, List.of(choice)))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThat(new PrivateAnchorChoiceSnapshot(CONTEXT, 0, 0, ISSUED,
                ISSUED.plusSeconds(24 * 60 * 60), List.of(choice))).isNotNull();
        assertThatThrownBy(() -> new PrivateAnchorChoiceSnapshot(CONTEXT, 0, 0, ISSUED,
                ISSUED.plusSeconds(24 * 60 * 60).plusNanos(1), List.of(choice)))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasNoCause();
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() ->
                new PrivateAnchorChoice(LOW, "Private label\u200B", Set.of(Category.QUEUE)));
        assertThat(failure.toString()).doesNotContain("Private label", LOW.toString());
    }

    private static void assertUnavailable(Fixture fixture, UUID actor, UUID journey) {
        assertThatThrownBy(() -> fixture.service().read(actor, journey))
                .isExactlyInstanceOf(PrivateAnchorChoicesUnavailable.class)
                .hasMessage("Private route choices are unavailable").hasNoCause();
    }

    private static RouteAnchor labeled(UUID id, String label) {
        return new RouteAnchor(id, point(), Set.of(Category.QUEUE), Optional.of(label));
    }

    private static LiveRouteContext context(Set<UUID> anchors) {
        return new LiveRouteContext(CONTEXT, ACTOR, JOURNEY, 11, anchors);
    }

    private static StoredLiveRouteContext stored(LiveRouteContext context, Instant issued,
            Instant expires, Optional<UUID> version) {
        return new StoredLiveRouteContext(context, issued, expires, version);
    }

    private static RouteRequest.Coordinate point() { return new RouteRequest.Coordinate(80, 13); }
    private static UUID uuid(long value) { return new UUID(1, value); }

    private static final class Fixture {
        final List<String> order = new ArrayList<>();
        final Authority authority = new Authority();
        final MutableClock clock = new MutableClock(ISSUED.plusSeconds(300), order);
        PresenceConsent consent = new PresenceConsent(ACTOR, JOURNEY, 7, true, true);
        Optional<StoredLiveRouteContext> stored = Optional.of(
                PrivateAnchorChoiceServiceTest.stored(context(Set.of(HIGH, LOW)), ISSUED, EXPIRES,
                        Optional.of(CATALOG)));
        ContributorAssessment restriction = ContributorAssessment.initial(ACTOR);
        RouteAnchorCatalog catalog = new RouteAnchorCatalog(CATALOG, List.of(
                labeled(HIGH, "High area"), labeled(LOW, "Low area"), labeled(uuid(50), "Unused")));
        boolean expireDuringRestrictionRead;

        PrivateAnchorChoiceService service() {
            PresenceConsentParticipant consents = new PresenceConsentParticipant() {
                @Override public PresenceConsent read(Journey journey) {
                    order.add("consent"); return consent;
                }
                @Override public PresenceConsent change(Journey journey, long generation, boolean sharing) {
                    throw new AssertionError("read service must not write consent");
                }
                @Override public PresenceConsent submitIntent(Journey journey, long generation,
                        boolean sharing) { throw new AssertionError("read service must not write consent"); }
            };
            LiveRouteContextParticipant contexts = new LiveRouteContextParticipant() {
                @Override public Optional<StoredLiveRouteContext> read(Journey journey) {
                    order.add("context"); return stored;
                }
                @Override public StoredLiveRouteContext replace(Journey journey, Set<UUID> ids,
                        java.time.Duration lifetime, Optional<UUID> expected, UUID id) {
                    throw new AssertionError("read service must not replace context");
                }
                @Override public StoredLiveRouteContext replaceBound(Journey journey, Set<UUID> ids,
                        java.time.Duration lifetime, Optional<UUID> expected, UUID id, UUID version) {
                    throw new AssertionError("read service must not replace context");
                }
            };
            ContributionRestrictionReader restrictions = actor -> {
                order.add("restriction");
                if (expireDuringRestrictionRead) clock.now = EXPIRES;
                return restriction;
            };
            return new PrivateAnchorChoiceService(authority, consents, contexts, restrictions,
                    catalog, clock);
        }
    }

    private static final class Authority implements JourneyWriteAuthority {
        Journey journey = Journey.start(JOURNEY, ACTOR, Journey.Kind.TRIP, ISSUED);
        int calls;
        @Override public <T> T withOwnedJourney(UUID actorId, UUID journeyId, Work<T> work) {
            calls++;
            return work.execute(journey);
        }
    }

    private static final class MutableClock extends Clock {
        Instant now;
        final List<String> order;
        MutableClock(Instant now, List<String> order) { this.now = now; this.order = order; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { order.add("clock"); return now; }
    }
}
