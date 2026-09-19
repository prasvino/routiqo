package com.routiqo.core.routeupdate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.moderation.application.ContributionRestrictionReader;
import com.routiqo.core.moderation.domain.ContributorAssessment;
import com.routiqo.core.privacy.application.PresenceConsentParticipant;
import com.routiqo.core.privacy.domain.PresenceConsent;
import com.routiqo.core.routeupdate.domain.LiveRouteContext;
import com.routiqo.core.routeupdate.domain.QuickSignalValue.Category;
import com.routiqo.core.routeupdate.domain.RouteAnchor;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import com.routiqo.core.routeupdate.domain.SignalIssuanceExpectation;
import com.routiqo.core.routeupdate.domain.StoredLiveRouteContext;
import com.routiqo.core.routing.domain.RouteRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExpectedSignalIssuanceTest {
    private static final UUID ACTOR = new UUID(1, 1);
    private static final UUID JOURNEY = new UUID(1, 2);
    private static final UUID CONTEXT = new UUID(1, 3);
    private static final UUID ANCHOR = new UUID(1, 4);
    private static final UUID CATALOG = new UUID(1, 5);
    private static final Instant START = Instant.parse("2026-09-19T12:00:00Z");

    @Test void exactPostReadExpiryCannotBeRoundedBackIntoValidity() {
        Fixture fixture = new Fixture(START.plusNanos(500), START.plusNanos(999));
        assertThatThrownBy(() -> fixture.issue()).isExactlyInstanceOf(SignalStorageDenied.class)
                .hasNoCause();
        assertThat(fixture.authority.calls).isEqualTo(1);
        var order = inOrder(fixture.consents, fixture.contexts, fixture.restrictions, fixture.clock);
        order.verify(fixture.consents).read(fixture.authority.journey);
        order.verify(fixture.contexts).read(fixture.authority.journey);
        order.verify(fixture.restrictions).read(ACTOR);
        order.verify(fixture.clock).instant();
        verifyNoInteractions(fixture.store);
    }

    @Test void matchingExpectedIssuanceUsesOneAuthorityAndExistingPersistencePrecision() {
        Fixture fixture = new Fixture(START.plusSeconds(60), START.plusNanos(999));
        var grant = fixture.issue();
        assertThat(fixture.authority.calls).isEqualTo(1);
        assertThat(grant.admission().issuedAt()).isEqualTo(START);
        assertThat(grant.admission().contextId()).isEqualTo(CONTEXT);
        assertThat(grant.admission().routeRevision()).isEqualTo(11);
        assertThat(grant.admission().consentGeneration()).isEqualTo(7);
        verify(fixture.store).reserveBudget(ACTOR, SignalStorageStore.BudgetAction.GRANT, START, 10);
        verify(fixture.store).insertGrant(grant);
    }

    private static final class Fixture {
        final Authority authority = new Authority();
        final PresenceConsentParticipant consents = mock(PresenceConsentParticipant.class);
        final LiveRouteContextParticipant contexts = mock(LiveRouteContextParticipant.class);
        final ContributionRestrictionReader restrictions = mock(ContributionRestrictionReader.class);
        final SignalStorageStore store = mock(SignalStorageStore.class);
        final Clock clock = mock(Clock.class);
        final CatalogSignalService signals;

        Fixture(Instant expires, Instant now) {
            when(consents.read(authority.journey))
                    .thenReturn(new PresenceConsent(ACTOR, JOURNEY, 7, true, true));
            when(contexts.read(authority.journey)).thenReturn(Optional.of(new StoredLiveRouteContext(
                    new LiveRouteContext(CONTEXT, ACTOR, JOURNEY, 11, Set.of(ANCHOR)),
                    START, expires, Optional.of(CATALOG))));
            when(restrictions.read(ACTOR)).thenReturn(ContributorAssessment.initial(ACTOR));
            when(clock.instant()).thenReturn(now);
            var catalog = new RouteAnchorCatalog(CATALOG, List.of(new RouteAnchor(ANCHOR,
                    new RouteRequest.Coordinate(80, 13), Set.of(Category.QUEUE))));
            signals = new CatalogSignalService(new SignalStorageService(authority, consents,
                    contexts, restrictions, store, clock), catalog);
        }

        com.routiqo.core.routeupdate.domain.SignalCommandGrant issue() {
            return signals.issueExpectedContext(ACTOR, JOURNEY, ANCHOR,
                    new SignalIssuanceExpectation(CONTEXT, 11, 7));
        }
    }

    private static final class Authority implements JourneyWriteAuthority {
        final Journey journey = Journey.start(JOURNEY, ACTOR, Journey.Kind.TRIP, START);
        int calls;
        @Override public <T> T withOwnedJourney(UUID actor, UUID journeyId, Work<T> work) {
            assertThat(actor).isEqualTo(ACTOR);
            assertThat(journeyId).isEqualTo(JOURNEY);
            calls++;
            return work.execute(journey);
        }
    }
}
