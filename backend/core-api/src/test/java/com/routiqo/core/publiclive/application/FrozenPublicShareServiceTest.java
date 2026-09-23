package com.routiqo.core.publiclive.application;

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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FrozenPublicShareServiceTest {
    private static final UUID PILOT = UUID.randomUUID(), ACTOR = UUID.randomUUID();
    private static final UUID JOURNEY = UUID.randomUUID(), COMMAND = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID(), PERSON = UUID.randomUUID();
    private static final UUID CONTEXT = UUID.randomUUID(), ANCHOR = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-23T10:01:00Z");

    private Journey journey = Journey.start(JOURNEY, ACTOR, Journey.Kind.TRIP, NOW.minusSeconds(600));
    private final PresenceConsentParticipant consents = mock(PresenceConsentParticipant.class);
    private final LiveRouteContextParticipant contexts = mock(LiveRouteContextParticipant.class);
    private final ContributionRestrictionReader restrictions = mock(ContributionRestrictionReader.class);
    private final VerifiedContributorReader verification = mock(VerifiedContributorReader.class);
    private final SignalStorageStore receipts = mock(SignalStorageStore.class);
    private final FrozenPublicShareStore store = mock(FrozenPublicShareStore.class);
    private final JourneyWriteAuthority journeys = new JourneyWriteAuthority() {
        @Override public <T> T withOwnedJourney(UUID actor, UUID journeyId, Work<T> work) {
            assertThat(actor).isEqualTo(ACTOR);
            assertThat(journeyId).isEqualTo(JOURNEY);
            return work.execute(journey);
        }
    };
    private final RouteAnchorCatalog catalog = new RouteAnchorCatalog(VERSION, List.of(
            new RouteAnchor(ANCHOR, new RouteRequest.Coordinate(80, 13),
                    Set.of(QuickSignalValue.Category.TRAFFIC))));
    private final FrozenPublicShareService service = new FrozenPublicShareService(PILOT,
            journeys, consents, contexts, restrictions, verification,
            catalog, receipts, store, Clock.fixed(NOW, ZoneOffset.UTC));

    private void eligible(QuickSignalValue value) {
        when(store.manifest(PILOT)).thenReturn(new FrozenPublicShareStore.Manifest(VERSION, Set.of(ANCHOR),
                NOW.minusSeconds(3600), NOW.plusSeconds(3600)));
        when(verification.currentForFrozenShare(ACTOR, NOW)).thenReturn(Optional.of(
                new VerifiedContributorReader.VerifiedContributor(PERSON, 2, NOW.plusSeconds(3600))));
        when(consents.read(journey)).thenReturn(new PresenceConsent(ACTOR, JOURNEY, 7, true, true));
        when(contexts.read(journey)).thenReturn(Optional.of(new StoredLiveRouteContext(
                new LiveRouteContext(CONTEXT, ACTOR, JOURNEY, 3, Set.of(ANCHOR)),
                NOW.minusSeconds(180), NOW.plusSeconds(600), Optional.of(VERSION))));
        when(restrictions.read(ACTOR)).thenReturn(new ContributorAssessment(ACTOR, 1,
                ContributorAssessment.State.ASSESSED, UUID.randomUUID(),
                NOW.minusSeconds(60), NOW.plusSeconds(3600)));
        var admission = new SignalAdmission(ACTOR, JOURNEY, CONTEXT, ANCHOR, 3, 7,
                Set.of(value.category()), NOW.minusSeconds(70), NOW.plusSeconds(20));
        when(receipts.findGrant(ACTOR, COMMAND)).thenReturn(Optional.of(new SignalCommandGrant(
                COMMAND, admission, 1, SignalCommandGrant.State.CONSUMED)));
        var signal = new QuickSignal(COMMAND, ACTOR, JOURNEY, ANCHOR, value,
                7, NOW.minusSeconds(60), NOW.plusSeconds(600));
        when(receipts.findReceipt(ACTOR, COMMAND)).thenReturn(Optional.of(
                new QuickSignalReceipt(signal, CONTEXT, 3, NOW.plusSeconds(700),
                        QuickSignalReceipt.State.ACTIVE)));
    }

    @Test void derivesOneServerWindowTrafficKeyFromCurrentPrivateSignal() {
        eligible(QuickSignalValue.TRAFFIC_SLOW);
        String key = "v2:" + ANCHOR + ":TRAFFIC_SLOW:1790157600";
        var ack = new FrozenPublicShareStore.Acknowledgement(PILOT, ACTOR, JOURNEY,
                COMMAND, REQUEST, key, NOW);
        when(store.freeze(PILOT, PERSON, ACTOR, JOURNEY, COMMAND, REQUEST, key)).thenReturn(ack);
        assertThat(service.share(ACTOR, JOURNEY, COMMAND, REQUEST)).isEqualTo(ack);
        verify(store).freeze(PILOT, PERSON, ACTOR, JOURNEY, COMMAND, REQUEST, key);
    }

    @Test void priorExactShareRecoversAfterJourneyAndAuthorityLoss() {
        String key = "v2:" + ANCHOR + ":TRAFFIC_SLOW:1790157600";
        var ack = new FrozenPublicShareStore.Acknowledgement(PILOT, ACTOR, JOURNEY,
                COMMAND, REQUEST, key, NOW);
        journey = journey.complete(ACTOR, NOW);
        when(store.findOwner(PILOT, ACTOR, JOURNEY, COMMAND, REQUEST)).thenReturn(ack);
        assertThat(service.share(ACTOR, JOURNEY, COMMAND, REQUEST)).isEqualTo(ack);
        verify(store, never()).freeze(any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(consents, contexts, restrictions, verification, receipts);
    }

    @Test void authorityLossAndNonTrafficCannotCreateNewFrozenInput() {
        eligible(QuickSignalValue.TRAFFIC_SLOW);
        when(restrictions.read(ACTOR)).thenReturn(ContributorAssessment.initial(ACTOR));
        assertThatThrownBy(() -> service.share(ACTOR, JOURNEY, COMMAND, REQUEST))
                .isInstanceOf(SecurityException.class);
        verify(store, never()).freeze(any(), any(), any(), any(), any(), any(), any());
        reset(restrictions, receipts);
        eligible(QuickSignalValue.PARKING_FULL);
        assertThatThrownBy(() -> service.share(ACTOR, JOURNEY, COMMAND, REQUEST))
                .isInstanceOf(SecurityException.class);
        verify(store, never()).freeze(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void anEarlierAcceptedWindowCannotBeRelabeledAfterItsClose() {
        eligible(QuickSignalValue.TRAFFIC_SLOW);
        var oldSignal = new QuickSignal(COMMAND, ACTOR, JOURNEY, ANCHOR,
                QuickSignalValue.TRAFFIC_SLOW, 7, NOW.minusSeconds(70), NOW.plusSeconds(600));
        when(receipts.findReceipt(ACTOR, COMMAND)).thenReturn(Optional.of(
                new QuickSignalReceipt(oldSignal, CONTEXT, 3, NOW.plusSeconds(700),
                        QuickSignalReceipt.State.ACTIVE)));
        assertThatThrownBy(() -> service.share(ACTOR, JOURNEY, COMMAND, REQUEST))
                .isInstanceOf(SecurityException.class);
        verify(store, never()).freeze(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void pilotIncludesItsLastWholeWindowAndExcludesTheNext() {
        eligible(QuickSignalValue.TRAFFIC_SLOW);
        Instant window = NOW.minusSeconds(60);
        String key = "v2:" + ANCHOR + ":TRAFFIC_SLOW:1790157600";
        when(store.manifest(PILOT)).thenReturn(new FrozenPublicShareStore.Manifest(
                VERSION, Set.of(ANCHOR), window.minusSeconds(300), window.plusSeconds(300)));
        var ack = new FrozenPublicShareStore.Acknowledgement(PILOT, ACTOR, JOURNEY,
                COMMAND, REQUEST, key, NOW);
        when(store.freeze(PILOT, PERSON, ACTOR, JOURNEY, COMMAND, REQUEST, key)).thenReturn(ack);
        assertThat(service.share(ACTOR, JOURNEY, COMMAND, REQUEST)).isEqualTo(ack);
        reset(store);
        when(store.manifest(PILOT)).thenReturn(new FrozenPublicShareStore.Manifest(
                VERSION, Set.of(ANCHOR), window.minusSeconds(300), window));
        assertThatThrownBy(() -> service.share(ACTOR, JOURNEY, COMMAND, REQUEST))
                .isInstanceOf(SecurityException.class);
        verify(store, never()).freeze(any(), any(), any(), any(), any(), any(), any());
    }
}
