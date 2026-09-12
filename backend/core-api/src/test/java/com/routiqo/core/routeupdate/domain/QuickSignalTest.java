package com.routiqo.core.routeupdate.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuickSignalTest {
    private static final Instant RECEIVED = Instant.parse("2026-09-12T12:00:00Z");
    private static final UUID SIGNAL = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID JOURNEY = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID ANCHOR = UUID.fromString("40000000-0000-0000-0000-000000000004");

    @Test void valueOwnsOneOfTheFiveClosedCategories() {
        assertThat(values(QuickSignalValue.Category.QUEUE)).containsExactly(
                QuickSignalValue.QUEUE_UNDER_5, QuickSignalValue.QUEUE_5_TO_15,
                QuickSignalValue.QUEUE_15_TO_30, QuickSignalValue.QUEUE_OVER_30);
        assertThat(values(QuickSignalValue.Category.TRAFFIC)).containsExactly(
                QuickSignalValue.TRAFFIC_MOVING, QuickSignalValue.TRAFFIC_SLOW,
                QuickSignalValue.TRAFFIC_VERY_SLOW, QuickSignalValue.TRAFFIC_STOPPED);
        assertThat(values(QuickSignalValue.Category.PARKING)).containsExactly(
                QuickSignalValue.PARKING_AVAILABLE, QuickSignalValue.PARKING_FILLING,
                QuickSignalValue.PARKING_FULL);
        assertThat(values(QuickSignalValue.Category.FOOD_QUEUE)).containsExactly(
                QuickSignalValue.FOOD_QUEUE_NONE, QuickSignalValue.FOOD_QUEUE_SHORT,
                QuickSignalValue.FOOD_QUEUE_LONG);
        assertThat(values(QuickSignalValue.Category.RESTROOM)).containsExactly(
                QuickSignalValue.RESTROOM_USABLE, QuickSignalValue.RESTROOM_BUSY,
                QuickSignalValue.RESTROOM_PROBLEM_REPORTED);
        assertThat(QuickSignalValue.values()).hasSize(17);
    }

    @Test void lifecycleIsHalfOpenAndUsesTheServerReceiptWindow() {
        var signal = signal(SIGNAL, ACTOR, JOURNEY, ANCHOR, QuickSignalValue.TRAFFIC_SLOW,
                7, RECEIVED, RECEIVED.plusSeconds(900));

        assertThat(signal.isCurrent(RECEIVED.minusNanos(1))).isFalse();
        assertThat(signal.isCurrent(RECEIVED)).isTrue();
        assertThat(signal.isCurrent(signal.expiresAt().minusNanos(1))).isTrue();
        assertThat(signal.isCurrent(signal.expiresAt())).isFalse();
        assertThat(signal.isCurrent(signal.expiresAt().plusSeconds(1))).isFalse();
        assertThat(signal.expiresAt()).isEqualTo(RECEIVED.plusSeconds(900));
        assertThatThrownBy(() -> signal.isCurrent(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Evaluation time is required")
                .hasNoCause();
    }

    @Test void rejectsMissingOrNilIdentifiersWithOneRedactedFailure() {
        UUID nil = new UUID(0, 0);
        for (int index = 0; index < 4; index++) {
            var identifiers = new UUID[] {SIGNAL, ACTOR, JOURNEY, ANCHOR};
            identifiers[index] = null;
            assertInvalid(identifiers[0], identifiers[1], identifiers[2], identifiers[3],
                    QuickSignalValue.PARKING_AVAILABLE, 0, RECEIVED, RECEIVED.plusSeconds(1));
            identifiers[index] = nil;
            assertInvalid(identifiers[0], identifiers[1], identifiers[2], identifiers[3],
                    QuickSignalValue.PARKING_AVAILABLE, 0, RECEIVED, RECEIVED.plusSeconds(1));
        }
    }

    @Test void rejectsMissingValuesInvalidGenerationAndInvalidLifetimes() {
        assertInvalid(SIGNAL, ACTOR, JOURNEY, ANCHOR, null, 0, RECEIVED, RECEIVED.plusSeconds(1));
        assertInvalid(SIGNAL, ACTOR, JOURNEY, ANCHOR, QuickSignalValue.PARKING_AVAILABLE,
                -1, RECEIVED, RECEIVED.plusSeconds(1));
        assertInvalid(SIGNAL, ACTOR, JOURNEY, ANCHOR, QuickSignalValue.PARKING_AVAILABLE,
                0, null, RECEIVED.plusSeconds(1));
        assertInvalid(SIGNAL, ACTOR, JOURNEY, ANCHOR, QuickSignalValue.PARKING_AVAILABLE,
                0, RECEIVED, null);
        assertInvalid(SIGNAL, ACTOR, JOURNEY, ANCHOR, QuickSignalValue.PARKING_AVAILABLE,
                0, RECEIVED, RECEIVED);
        assertInvalid(SIGNAL, ACTOR, JOURNEY, ANCHOR, QuickSignalValue.PARKING_AVAILABLE,
                0, RECEIVED, RECEIVED.minusNanos(1));
        assertInvalid(SIGNAL, ACTOR, JOURNEY, ANCHOR, QuickSignalValue.PARKING_AVAILABLE,
                0, RECEIVED, RECEIVED.plusSeconds(900).plusNanos(1));
        assertInvalid(SIGNAL, ACTOR, JOURNEY, ANCHOR, QuickSignalValue.PARKING_AVAILABLE,
                0, Instant.MIN, Instant.MAX);
        assertInvalid(SIGNAL, ACTOR, JOURNEY, ANCHOR, QuickSignalValue.PARKING_AVAILABLE,
                0, Instant.MAX, Instant.MIN);

        assertThat(signal(SIGNAL, ACTOR, JOURNEY, ANCHOR, QuickSignalValue.PARKING_AVAILABLE,
                Long.MAX_VALUE, RECEIVED, RECEIVED.plusSeconds(900)).consentGeneration())
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test void contributionSlotUsesActorAnchorAndCategoryButNotJourneyOrValue() {
        var base = signal(SIGNAL, ACTOR, JOURNEY, ANCHOR, QuickSignalValue.QUEUE_UNDER_5,
                2, RECEIVED, RECEIVED.plusSeconds(600));
        var sameSlotAcrossJourneyAndValue = signal(UUID.randomUUID(), ACTOR, UUID.randomUUID(), ANCHOR,
                QuickSignalValue.QUEUE_OVER_30, 3, RECEIVED.plusSeconds(1), RECEIVED.plusSeconds(601));

        assertThat(base.sharesContributionSlot(sameSlotAcrossJourneyAndValue)).isTrue();
        assertThat(base.sharesContributionSlot(signal(UUID.randomUUID(), ACTOR, JOURNEY, ANCHOR,
                QuickSignalValue.TRAFFIC_MOVING, 2, RECEIVED, RECEIVED.plusSeconds(1)))).isFalse();
        assertThat(base.sharesContributionSlot(signal(UUID.randomUUID(), UUID.randomUUID(), JOURNEY, ANCHOR,
                QuickSignalValue.QUEUE_UNDER_5, 2, RECEIVED, RECEIVED.plusSeconds(1)))).isFalse();
        assertThat(base.sharesContributionSlot(signal(UUID.randomUUID(), ACTOR, JOURNEY, UUID.randomUUID(),
                QuickSignalValue.QUEUE_UNDER_5, 2, RECEIVED, RECEIVED.plusSeconds(1)))).isFalse();
        assertThat(base.sharesContributionSlot(null)).isFalse();
    }

    @Test void exactReplayFingerprintCannotRenewOrChangeTheStoredSignal() {
        var signal = signal(SIGNAL, ACTOR, JOURNEY, ANCHOR, QuickSignalValue.RESTROOM_BUSY,
                8, RECEIVED, RECEIVED.plusSeconds(300));
        Instant unchangedExpiry = signal.expiresAt();

        assertThat(signal.matchesSubmission(ACTOR, JOURNEY, ANCHOR,
                QuickSignalValue.RESTROOM_BUSY, 8)).isTrue();
        assertThat(signal.matchesSubmission(UUID.randomUUID(), JOURNEY, ANCHOR,
                QuickSignalValue.RESTROOM_BUSY, 8)).isFalse();
        assertThat(signal.matchesSubmission(ACTOR, UUID.randomUUID(), ANCHOR,
                QuickSignalValue.RESTROOM_BUSY, 8)).isFalse();
        assertThat(signal.matchesSubmission(ACTOR, JOURNEY, UUID.randomUUID(),
                QuickSignalValue.RESTROOM_BUSY, 8)).isFalse();
        assertThat(signal.matchesSubmission(ACTOR, JOURNEY, ANCHOR,
                QuickSignalValue.RESTROOM_USABLE, 8)).isFalse();
        assertThat(signal.matchesSubmission(ACTOR, JOURNEY, ANCHOR,
                QuickSignalValue.RESTROOM_BUSY, 9)).isFalse();
        assertThat(signal.matchesSubmission(null, JOURNEY, ANCHOR,
                QuickSignalValue.RESTROOM_BUSY, 8)).isFalse();
        assertThat(signal.expiresAt()).isEqualTo(unchangedExpiry);

        var newCommand = signal(UUID.randomUUID(), ACTOR, JOURNEY, ANCHOR,
                QuickSignalValue.RESTROOM_BUSY, 8, RECEIVED, RECEIVED.plusSeconds(300));
        assertThat(newCommand).isNotEqualTo(signal);
        assertThat(newCommand.matchesSubmission(ACTOR, JOURNEY, ANCHOR,
                QuickSignalValue.RESTROOM_BUSY, 8)).isTrue();
    }

    @Test void stringRepresentationRedactsEverySignalField() {
        var signal = signal(SIGNAL, ACTOR, JOURNEY, ANCHOR, QuickSignalValue.FOOD_QUEUE_LONG,
                91, RECEIVED, RECEIVED.plusSeconds(10));

        assertThat(signal.toString()).isEqualTo("QuickSignal[private]")
                .doesNotContain(SIGNAL.toString(), ACTOR.toString(), JOURNEY.toString(), ANCHOR.toString(),
                        "FOOD_QUEUE_LONG", "91", RECEIVED.toString(), signal.expiresAt().toString());
    }

    private static List<QuickSignalValue> values(QuickSignalValue.Category category) {
        return Arrays.stream(QuickSignalValue.values()).filter(value -> value.category() == category).toList();
    }

    private static QuickSignal signal(UUID signalId, UUID actorId, UUID journeyId, UUID anchorId,
            QuickSignalValue value, long generation, Instant receivedAt, Instant expiresAt) {
        return new QuickSignal(signalId, actorId, journeyId, anchorId, value, generation, receivedAt, expiresAt);
    }

    private static void assertInvalid(UUID signalId, UUID actorId, UUID journeyId, UUID anchorId,
            QuickSignalValue value, long generation, Instant receivedAt, Instant expiresAt) {
        assertThatThrownBy(() -> signal(signalId, actorId, journeyId, anchorId,
                value, generation, receivedAt, expiresAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid quick signal")
                .hasNoCause();
    }
}
