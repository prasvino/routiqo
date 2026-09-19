package com.routiqo.core.routeupdate.application;

import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.moderation.application.ContributionRestrictionReader;
import com.routiqo.core.privacy.application.PresenceConsentParticipant;
import com.routiqo.core.routeupdate.domain.QuickSignal;
import com.routiqo.core.routeupdate.domain.QuickSignalReceipt;
import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.SignalAdmission;
import com.routiqo.core.routeupdate.domain.SignalCommandGrant;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignalCommandStopTest {
    private static final UUID ACTOR = new UUID(2, 1);
    private static final UUID JOURNEY = new UUID(2, 2);
    private static final UUID COMMAND = new UUID(2, 3);
    private static final UUID CONTEXT = new UUID(2, 4);
    private static final UUID ANCHOR = new UUID(2, 5);
    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");

    @Test void receiptIsReadFirstAndWithdrawnUnderOneAuthorityCallback() {
        Fixture fixture = new Fixture();
        QuickSignalReceipt active = fixture.receipt(QuickSignalReceipt.State.ACTIVE);
        when(fixture.store.findReceipt(ACTOR, COMMAND)).thenReturn(Optional.of(active));

        var result = fixture.service.stopCommand(ACTOR, JOURNEY, COMMAND);

        assertThat(result.receipt()).contains(active.withdraw());
        assertThat(fixture.authority.calls).isEqualTo(1);
        var order = inOrder(fixture.store, fixture.clock);
        order.verify(fixture.store).findReceipt(ACTOR, COMMAND);
        order.verify(fixture.clock).instant();
        order.verify(fixture.store).withdraw(active.withdraw());
        verify(fixture.store, never()).findGrant(ACTOR, COMMAND);
        verifyNoInteractions(fixture.consents, fixture.contexts, fixture.restrictions);
    }

    @Test void absentReceiptConsumesUnusedGrantWithoutConsultingCurrentAdmission() {
        Fixture fixture = new Fixture();
        SignalCommandGrant grant = fixture.grant(SignalCommandGrant.State.UNUSED);
        when(fixture.store.findReceipt(ACTOR, COMMAND)).thenReturn(Optional.empty());
        when(fixture.store.findGrant(ACTOR, COMMAND)).thenReturn(Optional.of(grant));

        var result = fixture.service.stopCommand(ACTOR, JOURNEY, COMMAND);

        assertThat(result.receipt()).isEmpty();
        assertThat(fixture.authority.calls).isEqualTo(1);
        verify(fixture.store).consumeGrant(grant.consume());
        verifyNoInteractions(fixture.clock, fixture.consents, fixture.contexts,
                fixture.restrictions);
    }

    @Test void consumedGrantIsAnExactNoOp() {
        Fixture fixture = new Fixture();
        SignalCommandGrant consumed = fixture.grant(SignalCommandGrant.State.CONSUMED);
        when(fixture.store.findReceipt(ACTOR, COMMAND)).thenReturn(Optional.empty());
        when(fixture.store.findGrant(ACTOR, COMMAND)).thenReturn(Optional.of(consumed));

        assertThat(fixture.service.stopCommand(ACTOR, JOURNEY, COMMAND).receipt()).isEmpty();
        verify(fixture.store, never()).consumeGrant(consumed);
        verifyNoInteractions(fixture.clock, fixture.consents, fixture.contexts,
                fixture.restrictions);
    }

    @Test void forgedAndFutureReceiptSnapshotsAreDeniedWithoutGrantFallbackOrMutation() {
        for (QuickSignalReceipt forged : Set.of(
                receipt(UUID.randomUUID(), ACTOR, JOURNEY, NOW.minusSeconds(30)),
                receipt(COMMAND, UUID.randomUUID(), JOURNEY, NOW.minusSeconds(30)),
                receipt(COMMAND, ACTOR, UUID.randomUUID(), NOW.minusSeconds(30)),
                receipt(COMMAND, ACTOR, JOURNEY, NOW.plusSeconds(1)))) {
            Fixture fixture = new Fixture();
            when(fixture.store.findReceipt(ACTOR, COMMAND)).thenReturn(Optional.of(forged));
            assertThatThrownBy(() -> fixture.service.stopCommand(ACTOR, JOURNEY, COMMAND))
                    .isExactlyInstanceOf(SignalStorageDenied.class).hasNoCause();
            verify(fixture.store, never()).findGrant(ACTOR, COMMAND);
            verify(fixture.store, never()).withdraw(forged.withdraw());
        }
    }

    @Test void forgedGrantSnapshotsAreDeniedWithoutConsumption() {
        for (SignalCommandGrant forged : Set.of(
                grant(COMMAND, UUID.randomUUID(), JOURNEY),
                grant(COMMAND, ACTOR, UUID.randomUUID()),
                grant(UUID.randomUUID(), ACTOR, JOURNEY))) {
            Fixture fixture = new Fixture();
            when(fixture.store.findReceipt(ACTOR, COMMAND)).thenReturn(Optional.empty());
            when(fixture.store.findGrant(ACTOR, COMMAND)).thenReturn(Optional.of(forged));
            assertThatThrownBy(() -> fixture.service.stopCommand(ACTOR, JOURNEY, COMMAND))
                    .isExactlyInstanceOf(SignalStorageDenied.class).hasNoCause();
            verify(fixture.store, never()).consumeGrant(forged.consume());
        }
    }

    private static final class Fixture {
        final Authority authority = new Authority();
        final PresenceConsentParticipant consents = mock(PresenceConsentParticipant.class);
        final LiveRouteContextParticipant contexts = mock(LiveRouteContextParticipant.class);
        final ContributionRestrictionReader restrictions = mock(ContributionRestrictionReader.class);
        final SignalStorageStore store = mock(SignalStorageStore.class);
        final Clock clock = mock(Clock.class);
        final SignalStorageService service = new SignalStorageService(authority, consents,
                contexts, restrictions, store, clock);

        Fixture() {
            when(clock.instant()).thenReturn(NOW);
        }

        SignalCommandGrant grant(SignalCommandGrant.State state) {
            return new SignalCommandGrant(COMMAND, new SignalAdmission(ACTOR, JOURNEY,
                    CONTEXT, ANCHOR, 1, 1, Set.of(QuickSignalValue.Category.QUEUE),
                    NOW.minusSeconds(120), NOW.minusSeconds(30)), 0, state);
        }

        QuickSignalReceipt receipt(QuickSignalReceipt.State state) {
            QuickSignalReceipt receipt = SignalCommandStopTest.receipt(
                    COMMAND, ACTOR, JOURNEY, NOW.minusSeconds(30));
            return state == QuickSignalReceipt.State.ACTIVE ? receipt
                    : state == QuickSignalReceipt.State.WITHDRAWN
                            ? receipt.withdraw() : receipt.supersede();
        }
    }

    private static SignalCommandGrant grant(UUID command, UUID actor, UUID journey) {
        return new SignalCommandGrant(command, new SignalAdmission(actor, journey,
                CONTEXT, ANCHOR, 1, 1, Set.of(QuickSignalValue.Category.QUEUE),
                NOW.minusSeconds(120), NOW.minusSeconds(30)), 0,
                SignalCommandGrant.State.UNUSED);
    }

    private static QuickSignalReceipt receipt(
            UUID command, UUID actor, UUID journey, Instant receivedAt) {
        QuickSignal signal = new QuickSignal(command, actor, journey, ANCHOR,
                QuickSignalValue.QUEUE_UNDER_5, 1, receivedAt,
                receivedAt.plusSeconds(60));
        return new QuickSignalReceipt(signal, CONTEXT, 1, receivedAt.plusSeconds(120),
                QuickSignalReceipt.State.ACTIVE);
    }

    private static final class Authority implements JourneyWriteAuthority {
        final Journey journey = Journey.start(JOURNEY, ACTOR, Journey.Kind.TRIP,
                NOW.minusSeconds(300));
        int calls;

        @Override public <T> T withOwnedJourney(UUID actor, UUID journeyId, Work<T> work) {
            assertThat(actor).isEqualTo(ACTOR);
            assertThat(journeyId).isEqualTo(JOURNEY);
            calls++;
            return work.execute(journey);
        }
    }
}
