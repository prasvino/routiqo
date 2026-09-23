package com.routiqo.core.publiclive.application;

import com.routiqo.core.identity.application.AccountWriteAuthority;
import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.moderation.application.ContributionRestrictionReader;
import com.routiqo.core.moderation.domain.ContributorAssessment;
import com.routiqo.core.privacy.application.PresenceConsentParticipant;
import com.routiqo.core.privacy.domain.PresenceConsent;
import com.routiqo.core.routeupdate.application.LiveRouteContextParticipant;
import com.routiqo.core.routeupdate.application.SignalStorageStore;
import com.routiqo.core.routeupdate.domain.*;
import com.routiqo.core.routing.domain.RouteRequest;
import com.routiqo.core.verification.application.VerifiedContributorReader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CommunityTrafficShareServiceTest {
    private static final UUID ACTOR = UUID.randomUUID(), JOURNEY = UUID.randomUUID();
    private static final UUID COMMAND = UUID.randomUUID(), CONTEXT = UUID.randomUUID();
    private static final UUID ANCHOR = UUID.randomUUID(), VERSION = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-23T10:01:00Z");
    private final Journey active = Journey.start(JOURNEY, ACTOR, Journey.Kind.TRIP,
            NOW.minusSeconds(600));
    private Journey journey = active;
    private final PresenceConsentParticipant consents = mock(PresenceConsentParticipant.class);
    private final LiveRouteContextParticipant contexts = mock(LiveRouteContextParticipant.class);
    private final ContributionRestrictionReader restrictions = mock(ContributionRestrictionReader.class);
    private final VerifiedContributorReader verification = mock(VerifiedContributorReader.class);
    private final SignalStorageStore receipts = mock(SignalStorageStore.class);
    private final CommunityTrafficCandidateStore store = mock(CommunityTrafficCandidateStore.class);
    private final JourneyWriteAuthority journeys = new JourneyWriteAuthority() {
        @Override public <T> T withOwnedJourney(UUID actor, UUID id, Work<T> work) {
            assertThat(actor).isEqualTo(ACTOR);
            assertThat(id).isEqualTo(JOURNEY);
            return work.execute(journey);
        }
    };
    private final AccountWriteAuthority accounts = new AccountWriteAuthority() {
        @Override public <T> T withEnabledAccount(UUID actor, Work<T> work) {
            assertThat(actor).isEqualTo(ACTOR);
            return work.execute();
        }
    };
    private final RouteAnchorCatalog catalog = new RouteAnchorCatalog(VERSION, List.of(
            new RouteAnchor(ANCHOR, new RouteRequest.Coordinate(80, 13),
                    Set.of(QuickSignalValue.Category.TRAFFIC))));
    private final CommunityTrafficShareService service = new CommunityTrafficShareService(
            journeys, accounts, consents, contexts, restrictions, verification,
            catalog, receipts, store, Clock.fixed(NOW, ZoneOffset.UTC));

    private void eligible() {
        when(verification.currentForFrozenShare(ACTOR, NOW)).thenReturn(Optional.of(
                new VerifiedContributorReader.VerifiedContributor(UUID.randomUUID(), 2,
                        NOW.plusSeconds(3600))));
        when(consents.read(active)).thenReturn(new PresenceConsent(ACTOR, JOURNEY, 7, true, true));
        when(contexts.read(active)).thenReturn(Optional.of(new StoredLiveRouteContext(
                new LiveRouteContext(CONTEXT, ACTOR, JOURNEY, 3, Set.of(ANCHOR)),
                NOW.minusSeconds(180), NOW.plusSeconds(600), Optional.of(VERSION))));
        // The production JDBC restriction projection returns UNASSESSED for an
        // unrestricted account; ASSESSED is not a persisted state there.
        when(restrictions.read(ACTOR)).thenReturn(ContributorAssessment.initial(ACTOR));
        var admission = new SignalAdmission(ACTOR, JOURNEY, CONTEXT, ANCHOR, 3, 7,
                Set.of(QuickSignalValue.Category.TRAFFIC), NOW.minusSeconds(70),
                NOW.plusSeconds(20));
        when(receipts.findGrant(ACTOR, COMMAND)).thenReturn(Optional.of(new SignalCommandGrant(
                COMMAND, admission, 0, SignalCommandGrant.State.CONSUMED)));
        var signal = new QuickSignal(COMMAND, ACTOR, JOURNEY, ANCHOR,
                QuickSignalValue.TRAFFIC_SLOW, 7, NOW.minusSeconds(60), NOW.plusSeconds(600));
        when(receipts.findReceipt(ACTOR, COMMAND)).thenReturn(Optional.of(
                new QuickSignalReceipt(signal, CONTEXT, 3, NOW.plusSeconds(700),
                        QuickSignalReceipt.State.ACTIVE)));
        when(store.insert(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test void newShareUsesPrivateReceiptAndFreshV3PurposeOnly() {
        eligible();
        var result = service.share(ACTOR, JOURNEY, COMMAND, REQUEST);
        assertThat(result.requestId()).isEqualTo(REQUEST);
        assertThat(result.windowStart()).isEqualTo(Instant.parse("2026-09-23T10:00:00Z"));
        assertThat(result.trafficValue()).isEqualTo("TRAFFIC_SLOW");
        assertThat(result.expiresAt()).isEqualTo(result.windowEndsAt().plusSeconds(24 * 3600));
        verify(store).insert(any());
    }

    @Test void exactRetryRecoversAfterCompletionWithoutReadingStaleAuthority() {
        var prior = new CommunityTrafficCandidateStore.Candidate(UUID.randomUUID(), ACTOR,
                JOURNEY, COMMAND, REQUEST, ANCHOR, "TRAFFIC_SLOW",
                Instant.parse("2026-09-23T10:00:00Z"), VERSION, 7,
                NOW.minusSeconds(60), NOW, NOW.plusSeconds(24 * 3600),
                CommunityTrafficCandidateStore.State.STOPPED);
        journey = active.complete(ACTOR, NOW);
        when(store.find(ACTOR, COMMAND)).thenReturn(prior);
        assertThat(service.share(ACTOR, JOURNEY, COMMAND, REQUEST)).isEqualTo(prior);
        assertThatThrownBy(() -> service.share(ACTOR, JOURNEY, COMMAND, UUID.randomUUID()))
                .isInstanceOf(CommunityTrafficConflict.class);
        verifyNoInteractions(consents, contexts, verification, receipts);
    }

    @Test void ghostOrChangedConsentCannotCreateNewCandidateButStopStillWorks() {
        eligible();
        when(consents.read(active)).thenReturn(new PresenceConsent(ACTOR, JOURNEY, 8, false, true));
        assertThatThrownBy(() -> service.share(ACTOR, JOURNEY, COMMAND, REQUEST))
                .isInstanceOf(SecurityException.class);
        verify(store, never()).insert(any());
        journey = active.complete(ACTOR, NOW);
        service.stop(ACTOR, JOURNEY, COMMAND);
        verify(store).stop(ACTOR, JOURNEY, COMMAND, NOW);
    }

    @Test void suspendedContributorCannotCreateCandidate() {
        eligible();
        when(restrictions.read(ACTOR)).thenReturn(
                ContributorAssessment.initial(ACTOR).suspend(0));
        assertThatThrownBy(() -> service.share(ACTOR, JOURNEY, COMMAND, REQUEST))
                .isInstanceOf(SecurityException.class);
        verify(store, never()).insert(any());
    }

    @Test void privateReceiptWithdrawalAndCatalogChangeCannotCreateCandidate() {
        eligible();
        var signal = new QuickSignal(COMMAND, ACTOR, JOURNEY, ANCHOR,
                QuickSignalValue.TRAFFIC_SLOW, 7, NOW.minusSeconds(60), NOW.plusSeconds(600));
        when(receipts.findReceipt(ACTOR, COMMAND)).thenReturn(Optional.of(
                new QuickSignalReceipt(signal, CONTEXT, 3, NOW.plusSeconds(700),
                        QuickSignalReceipt.State.WITHDRAWN)));
        assertThatThrownBy(() -> service.share(ACTOR, JOURNEY, COMMAND, REQUEST))
                .isInstanceOf(SecurityException.class);
        when(receipts.findReceipt(ACTOR, COMMAND)).thenReturn(Optional.of(
                new QuickSignalReceipt(signal, CONTEXT, 3, NOW.plusSeconds(700),
                        QuickSignalReceipt.State.ACTIVE)));
        when(contexts.read(active)).thenReturn(Optional.of(new StoredLiveRouteContext(
                new LiveRouteContext(CONTEXT, ACTOR, JOURNEY, 3, Set.of(ANCHOR)),
                NOW.minusSeconds(180), NOW.plusSeconds(600), Optional.of(UUID.randomUUID()))));
        assertThatThrownBy(() -> service.share(ACTOR, JOURNEY, COMMAND, REQUEST))
                .isInstanceOf(SecurityException.class);
        verify(store, never()).insert(any());
    }
}
