package com.routiqo.core.routeupdate.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuickSignalReceiptTest {
    private static final Instant RECEIVED = Instant.parse("2026-09-12T12:00:00Z");
    private static final UUID SIGNAL = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID JOURNEY = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID ANCHOR = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID CONTEXT = UUID.fromString("50000000-0000-0000-0000-000000000005");

    @Test void acceptsExplicitRetentionAtSignalExpiryAndAtTheTwentyFourHourCeiling() {
        var signal = signal(RECEIVED, RECEIVED.plusSeconds(900));
        var shortest = receipt(signal, CONTEXT, Long.MAX_VALUE, signal.expiresAt(),
                QuickSignalReceipt.State.ACTIVE);
        var longest = receipt(signal, CONTEXT, 0, RECEIVED.plusSeconds(24 * 60 * 60),
                QuickSignalReceipt.State.ACTIVE);

        assertThat(shortest.retainUntil()).isEqualTo(signal.expiresAt());
        assertThat(shortest.routeRevision()).isEqualTo(Long.MAX_VALUE);
        assertThat(longest.retainUntil()).isEqualTo(RECEIVED.plusSeconds(24 * 60 * 60));
    }

    @Test void rejectsInvalidContextRevisionStateAndRetentionWithOneRedactedFailure() {
        var signal = signal(RECEIVED, RECEIVED.plusSeconds(60));
        assertInvalid(null, CONTEXT, 0, RECEIVED.plusSeconds(60), QuickSignalReceipt.State.ACTIVE);
        assertInvalid(signal, null, 0, RECEIVED.plusSeconds(60), QuickSignalReceipt.State.ACTIVE);
        assertInvalid(signal, new UUID(0, 0), 0, RECEIVED.plusSeconds(60), QuickSignalReceipt.State.ACTIVE);
        assertInvalid(signal, CONTEXT, -1, RECEIVED.plusSeconds(60), QuickSignalReceipt.State.ACTIVE);
        assertInvalid(signal, CONTEXT, 0, null, QuickSignalReceipt.State.ACTIVE);
        assertInvalid(signal, CONTEXT, 0, RECEIVED.plusSeconds(60), null);
        assertInvalid(signal, CONTEXT, 0, RECEIVED.plusSeconds(59), QuickSignalReceipt.State.ACTIVE);
        assertInvalid(signal, CONTEXT, 0, RECEIVED.plusSeconds(24 * 60 * 60).plusNanos(1),
                QuickSignalReceipt.State.ACTIVE);
    }

    @Test void extremeInstantsFailWithoutOverflowOrLeakingDetails() {
        var earliest = signal(Instant.MIN, Instant.MIN.plusSeconds(1));
        assertInvalid(earliest, CONTEXT, 0, Instant.MAX, QuickSignalReceipt.State.ACTIVE);

        var latest = signal(Instant.MAX.minusSeconds(1), Instant.MAX);
        assertInvalid(latest, CONTEXT, 0, Instant.MAX.minusNanos(1), QuickSignalReceipt.State.ACTIVE);
    }

    @Test void receiptRetentionIsHalfOpenAndNullTimeIsIneligible() {
        var signal = signal(RECEIVED, RECEIVED.plusSeconds(30));
        var receipt = receipt(signal, CONTEXT, 4, RECEIVED.plusSeconds(120),
                QuickSignalReceipt.State.ACTIVE);

        assertThat(receipt.isRetainedAt(null)).isFalse();
        assertThat(receipt.isRetainedAt(RECEIVED.minusNanos(1))).isFalse();
        assertThat(receipt.isRetainedAt(RECEIVED)).isTrue();
        assertThat(receipt.isRetainedAt(receipt.retainUntil().minusNanos(1))).isTrue();
        assertThat(receipt.isRetainedAt(receipt.retainUntil())).isFalse();
    }

    @Test void evidenceFreshnessRequiresActiveStateReceiptRetentionAndSignalLifetime() {
        var signal = signal(RECEIVED, RECEIVED.plusSeconds(30));
        var receipt = receipt(signal, CONTEXT, 4, RECEIVED.plusSeconds(120),
                QuickSignalReceipt.State.ACTIVE);

        assertThat(receipt.isEvidenceCurrent(null)).isFalse();
        assertThat(receipt.isEvidenceCurrent(RECEIVED.minusNanos(1))).isFalse();
        assertThat(receipt.isEvidenceCurrent(RECEIVED)).isTrue();
        assertThat(receipt.isEvidenceCurrent(signal.expiresAt().minusNanos(1))).isTrue();
        assertThat(receipt.isEvidenceCurrent(signal.expiresAt())).isFalse();
        assertThat(receipt.isEvidenceCurrent(receipt.retainUntil().minusNanos(1))).isFalse();
        assertThat(receipt.withdraw().isEvidenceCurrent(RECEIVED)).isFalse();
        assertThat(receipt.supersede().isEvidenceCurrent(RECEIVED)).isFalse();
    }

    @Test void firstTerminalTransitionWinsAndNeverRenewsOrErasesEvidence() {
        var signal = signal(RECEIVED, RECEIVED.plusSeconds(30));
        var active = receipt(signal, CONTEXT, 9, RECEIVED.plusSeconds(120),
                QuickSignalReceipt.State.ACTIVE);

        var withdrawn = active.withdraw();
        assertThat(withdrawn.state()).isEqualTo(QuickSignalReceipt.State.WITHDRAWN);
        assertThat(withdrawn.signal()).isSameAs(signal);
        assertThat(withdrawn.contextId()).isEqualTo(CONTEXT);
        assertThat(withdrawn.routeRevision()).isEqualTo(9);
        assertThat(withdrawn.retainUntil()).isEqualTo(active.retainUntil());
        assertThat(withdrawn.withdraw()).isSameAs(withdrawn);
        assertThat(withdrawn.supersede()).isSameAs(withdrawn);

        var superseded = active.supersede();
        assertThat(superseded.state()).isEqualTo(QuickSignalReceipt.State.SUPERSEDED);
        assertThat(superseded.supersede()).isSameAs(superseded);
        assertThat(superseded.withdraw()).isSameAs(superseded);
    }

    @Test void exactReplayMatchesAfterExpiryAndWithdrawalButChangedFingerprintDoesNot() {
        var signal = signal(RECEIVED, RECEIVED.plusSeconds(30));
        var receipt = receipt(signal, CONTEXT, 12, RECEIVED.plusSeconds(120),
                QuickSignalReceipt.State.ACTIVE).withdraw();

        assertThat(receipt.matchesSubmission(ACTOR, JOURNEY, ANCHOR,
                QuickSignalValue.RESTROOM_BUSY, 7, CONTEXT, 12)).isTrue();
        assertThat(receipt.isEvidenceCurrent(RECEIVED.plusSeconds(31))).isFalse();
        assertThat(receipt.matchesSubmission(UUID.randomUUID(), JOURNEY, ANCHOR,
                QuickSignalValue.RESTROOM_BUSY, 7, CONTEXT, 12)).isFalse();
        assertThat(receipt.matchesSubmission(ACTOR, UUID.randomUUID(), ANCHOR,
                QuickSignalValue.RESTROOM_BUSY, 7, CONTEXT, 12)).isFalse();
        assertThat(receipt.matchesSubmission(ACTOR, JOURNEY, UUID.randomUUID(),
                QuickSignalValue.RESTROOM_BUSY, 7, CONTEXT, 12)).isFalse();
        assertThat(receipt.matchesSubmission(ACTOR, JOURNEY, ANCHOR,
                QuickSignalValue.RESTROOM_USABLE, 7, CONTEXT, 12)).isFalse();
        assertThat(receipt.matchesSubmission(ACTOR, JOURNEY, ANCHOR,
                QuickSignalValue.RESTROOM_BUSY, 8, CONTEXT, 12)).isFalse();
        assertThat(receipt.matchesSubmission(ACTOR, JOURNEY, ANCHOR,
                QuickSignalValue.RESTROOM_BUSY, 7, UUID.randomUUID(), 12)).isFalse();
        assertThat(receipt.matchesSubmission(ACTOR, JOURNEY, ANCHOR,
                QuickSignalValue.RESTROOM_BUSY, 7, CONTEXT, 13)).isFalse();
        assertThat(receipt.matchesSubmission(null, JOURNEY, ANCHOR,
                QuickSignalValue.RESTROOM_BUSY, 7, CONTEXT, 12)).isFalse();
    }

    @Test void stringRepresentationRedactsReceiptAndSignalMetadata() {
        var signal = signal(RECEIVED, RECEIVED.plusSeconds(30));
        var receipt = receipt(signal, CONTEXT, 83, RECEIVED.plusSeconds(120),
                QuickSignalReceipt.State.SUPERSEDED);

        assertThat(receipt.toString()).isEqualTo("QuickSignalReceipt[private]")
                .doesNotContain(SIGNAL.toString(), ACTOR.toString(), JOURNEY.toString(), ANCHOR.toString(),
                        CONTEXT.toString(), "RESTROOM_BUSY", "83", RECEIVED.toString(),
                        receipt.retainUntil().toString(), "SUPERSEDED");
    }

    private static QuickSignal signal(Instant receivedAt, Instant expiresAt) {
        return new QuickSignal(SIGNAL, ACTOR, JOURNEY, ANCHOR, QuickSignalValue.RESTROOM_BUSY,
                7, receivedAt, expiresAt);
    }

    private static QuickSignalReceipt receipt(QuickSignal signal, UUID contextId, long revision,
            Instant retainUntil, QuickSignalReceipt.State state) {
        return new QuickSignalReceipt(signal, contextId, revision, retainUntil, state);
    }

    private static void assertInvalid(QuickSignal signal, UUID contextId, long revision,
            Instant retainUntil, QuickSignalReceipt.State state) {
        assertThatThrownBy(() -> receipt(signal, contextId, revision, retainUntil, state))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid quick signal receipt")
                .hasNoCause();
    }
}
