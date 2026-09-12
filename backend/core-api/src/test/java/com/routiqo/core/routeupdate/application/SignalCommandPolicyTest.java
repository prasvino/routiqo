package com.routiqo.core.routeupdate.application;

import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.privacy.domain.PresenceConsent;
import com.routiqo.core.routeupdate.domain.LiveRouteContext;
import com.routiqo.core.routeupdate.domain.QuickSignal;
import com.routiqo.core.routeupdate.domain.QuickSignalReceipt;
import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.SignalAdmission;
import com.routiqo.core.routeupdate.domain.SignalCommandGrant;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignalCommandPolicyTest {
    private static final UUID COMMAND = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID JOURNEY = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID CONTEXT = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID ANCHOR = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final long REVISION = 4;
    private static final long GENERATION = 7;
    private static final Instant STARTED = Instant.parse("2026-09-12T12:00:00Z");
    private static final Instant ISSUED = STARTED.plusSeconds(60);
    private static final Instant RECEIVED = ISSUED.plusSeconds(10);
    private static final Instant NOW = RECEIVED.plusSeconds(5);

    private final SignalCommandPolicy policy = new SignalCommandPolicy();

    @Test void currentUnusedGrantAndCurrentAuthorityProduceOnlyANewAcceptanceCandidate() {
        assertThat(decide(null, grant(), context(), journey(), consent(), NOW))
                .isEqualTo(SignalCommandPolicy.Decision.NEW_ACCEPTANCE_CANDIDATE);
    }

    @Test void retainedExactReplayIgnoresGrantAndCurrentEvidenceEligibility() {
        var retained = receipt(RECEIVED.plusSeconds(300)).withdraw();
        var completedJourney = journey().complete(ACTOR, NOW);
        var unrelatedConsumedGrant = new SignalCommandGrant(UUID.randomUUID(), admission(UUID.randomUUID()),
                SignalCommandGrant.State.CONSUMED);

        assertThat(policy.decide(ACTOR, COMMAND, submission(), retained, unrelatedConsumedGrant,
                null, completedJourney, null, ISSUED.plusSeconds(120)))
                .isEqualTo(SignalCommandPolicy.Decision.RETAINED_REPLAY);
        assertThat(retained.isEvidenceCurrent(ISSUED.plusSeconds(120))).isFalse();
    }

    @Test void everyChangedFieldOnARetainedCommandConflicts() {
        var receipt = receipt(RECEIVED.plusSeconds(120));
        assertConflict(receipt, submission(UUID.randomUUID(), ANCHOR, QuickSignalValue.QUEUE_OVER_30,
                GENERATION, CONTEXT, REVISION));
        assertConflict(receipt, submission(JOURNEY, UUID.randomUUID(), QuickSignalValue.QUEUE_OVER_30,
                GENERATION, CONTEXT, REVISION));
        assertConflict(receipt, submission(JOURNEY, ANCHOR, QuickSignalValue.QUEUE_5_TO_15,
                GENERATION, CONTEXT, REVISION));
        assertConflict(receipt, submission(JOURNEY, ANCHOR, QuickSignalValue.QUEUE_OVER_30,
                GENERATION + 1, CONTEXT, REVISION));
        assertConflict(receipt, submission(JOURNEY, ANCHOR, QuickSignalValue.QUEUE_OVER_30,
                GENERATION, UUID.randomUUID(), REVISION));
        assertConflict(receipt, submission(JOURNEY, ANCHOR, QuickSignalValue.QUEUE_OVER_30,
                GENERATION, CONTEXT, REVISION + 1));
    }

    @Test void retainedRowsDenyBeforeComparisonForWrongActorCommandOrJourneyOwnership() {
        var receipt = receipt(RECEIVED.plusSeconds(120));
        assertThat(policy.decide(UUID.randomUUID(), COMMAND, submission(), receipt, grant(),
                context(), journey(), consent(), NOW)).isEqualTo(SignalCommandPolicy.Decision.DENIED);
        assertThat(policy.decide(ACTOR, UUID.randomUUID(), submission(), receipt, grant(),
                context(), journey(), consent(), NOW)).isEqualTo(SignalCommandPolicy.Decision.DENIED);
        assertThat(policy.decide(ACTOR, COMMAND, submission(), receipt, grant(), context(),
                Journey.start(JOURNEY, UUID.randomUUID(), Journey.Kind.TRIP, STARTED), consent(), NOW))
                .isEqualTo(SignalCommandPolicy.Decision.DENIED);
        assertThat(policy.decide(ACTOR, COMMAND, submission(), receipt, grant(), context(),
                Journey.start(UUID.randomUUID(), ACTOR, Journey.Kind.TRIP, STARTED), consent(), NOW))
                .isEqualTo(SignalCommandPolicy.Decision.DENIED);
    }

    @Test void futureDatedReceiptDeniesInsteadOfFallingThroughToAValidGrant() {
        Instant futureReceived = NOW.plusNanos(1);
        var futureReceipt = receipt(futureReceived, futureReceived.plusSeconds(20),
                futureReceived.plusSeconds(60));

        assertThat(decide(futureReceipt, grant(), context(), journey(), consent(), NOW))
                .isEqualTo(SignalCommandPolicy.Decision.DENIED);
    }

    @Test void logicallyExpiredReceiptCannotConcealPriorAcceptanceBehindAnUnusedGrant() {
        var expired = receipt(RECEIVED.plusSeconds(20));
        Instant atReceiptExpiry = expired.retainUntil();

        assertThat(decide(expired, grant(), context(), journey(), consent(), atReceiptExpiry))
                .isEqualTo(SignalCommandPolicy.Decision.DENIED);
    }

    @Test void expiredReceiptWithWrongActorOrCommandAlwaysFailsClosed() {
        var expired = receipt(RECEIVED.plusSeconds(20));
        Instant atReceiptExpiry = expired.retainUntil();
        var wrongActorSignal = new QuickSignal(COMMAND, UUID.randomUUID(), JOURNEY, ANCHOR,
                QuickSignalValue.QUEUE_OVER_30, GENERATION, RECEIVED, RECEIVED.plusSeconds(20));
        var wrongActorReceipt = new QuickSignalReceipt(wrongActorSignal, CONTEXT, REVISION,
                atReceiptExpiry, QuickSignalReceipt.State.ACTIVE);
        var wrongCommandSignal = new QuickSignal(UUID.randomUUID(), ACTOR, JOURNEY, ANCHOR,
                QuickSignalValue.QUEUE_OVER_30, GENERATION, RECEIVED, RECEIVED.plusSeconds(20));
        var wrongCommandReceipt = new QuickSignalReceipt(wrongCommandSignal, CONTEXT, REVISION,
                atReceiptExpiry, QuickSignalReceipt.State.ACTIVE);

        assertThat(decide(wrongActorReceipt, grant(), context(), journey(), consent(), atReceiptExpiry))
                .isEqualTo(SignalCommandPolicy.Decision.DENIED);
        assertThat(decide(wrongCommandReceipt, grant(), context(), journey(), consent(), atReceiptExpiry))
                .isEqualTo(SignalCommandPolicy.Decision.DENIED);
    }

    @Test void expiredShortReceiptCannotResetAConsumedStillCurrentGrant() {
        var expired = receipt(RECEIVED.plusSeconds(20));
        var consumed = grant().consume();
        Instant atReceiptExpiry = expired.retainUntil();

        assertThat(consumed.admission().isCurrent(atReceiptExpiry)).isTrue();
        assertThat(decide(expired, consumed, context(), journey(), consent(), atReceiptExpiry))
                .isEqualTo(SignalCommandPolicy.Decision.DENIED);
    }

    @Test void missingConsumedFutureExpiredAndMismatchedGrantsDenyNewAcceptance() {
        assertDenied(null, null, context(), journey(), consent(), NOW);
        assertDenied(null, grant().consume(), context(), journey(), consent(), NOW);
        assertDenied(null, grant(), context(), journey(), consent(), ISSUED.minusNanos(1));
        assertDenied(null, grant(), context(), journey(), consent(), grant().admission().expiresAt());

        var wrongCommand = new SignalCommandGrant(UUID.randomUUID(), admission(ACTOR),
                SignalCommandGrant.State.UNUSED);
        assertDenied(null, wrongCommand, context(), journey(), consent(), NOW);
        var wrongActor = new SignalCommandGrant(COMMAND, admission(UUID.randomUUID()),
                SignalCommandGrant.State.UNUSED);
        assertDenied(null, wrongActor, context(), journey(), consent(), NOW);
    }

    @Test void admissionFingerprintAndCurrentAuthorityMustRemainExactForANewCandidate() {
        assertDenied(null, grant(), context(), journey(),
                new PresenceConsent(ACTOR, JOURNEY, GENERATION + 1, true, true), NOW);
        assertDenied(null, grant(), new LiveRouteContext(CONTEXT, ACTOR, JOURNEY,
                REVISION + 1, Set.of(ANCHOR)), journey(), consent(), NOW);
        assertDenied(null, grant(), context(), journey().complete(ACTOR, NOW), consent(), NOW);
        assertDenied(null, grant(), context(), journey(), consent().changeSharing(false), NOW);

        assertSubmissionDenied(submission(UUID.randomUUID(), ANCHOR, QuickSignalValue.QUEUE_OVER_30,
                GENERATION, CONTEXT, REVISION));
        assertSubmissionDenied(submission(JOURNEY, UUID.randomUUID(), QuickSignalValue.QUEUE_OVER_30,
                GENERATION, CONTEXT, REVISION));
        assertSubmissionDenied(submission(JOURNEY, ANCHOR, QuickSignalValue.TRAFFIC_SLOW,
                GENERATION, CONTEXT, REVISION));
        assertSubmissionDenied(submission(JOURNEY, ANCHOR, QuickSignalValue.QUEUE_OVER_30,
                GENERATION + 1, CONTEXT, REVISION));
        assertSubmissionDenied(submission(JOURNEY, ANCHOR, QuickSignalValue.QUEUE_OVER_30,
                GENERATION, UUID.randomUUID(), REVISION));
        assertSubmissionDenied(submission(JOURNEY, ANCHOR, QuickSignalValue.QUEUE_OVER_30,
                GENERATION, CONTEXT, REVISION + 1));
    }

    @Test void nullAndStructurallyInvalidInputsDenyWithoutDiagnostics() {
        var nil = new UUID(0, 0);
        assertThat(policy.decide(null, COMMAND, submission(), null, grant(), context(), journey(), consent(), NOW))
                .isEqualTo(SignalCommandPolicy.Decision.DENIED);
        assertThat(policy.decide(nil, COMMAND, submission(), null, grant(), context(), journey(), consent(), NOW))
                .isEqualTo(SignalCommandPolicy.Decision.DENIED);
        assertThat(policy.decide(ACTOR, null, submission(), null, grant(), context(), journey(), consent(), NOW))
                .isEqualTo(SignalCommandPolicy.Decision.DENIED);
        assertThat(policy.decide(ACTOR, nil, submission(), null, grant(), context(), journey(), consent(), NOW))
                .isEqualTo(SignalCommandPolicy.Decision.DENIED);
        assertThat(policy.decide(ACTOR, COMMAND, null, null, grant(), context(), journey(), consent(), NOW))
                .isEqualTo(SignalCommandPolicy.Decision.DENIED);
        assertThat(policy.decide(ACTOR, COMMAND, submission(), null, grant(), context(), null, consent(), NOW))
                .isEqualTo(SignalCommandPolicy.Decision.DENIED);
        assertThat(policy.decide(ACTOR, COMMAND, submission(), null, grant(), null, journey(), consent(), NOW))
                .isEqualTo(SignalCommandPolicy.Decision.DENIED);
        assertThat(policy.decide(ACTOR, COMMAND, submission(), null, grant(), context(), journey(), null, NOW))
                .isEqualTo(SignalCommandPolicy.Decision.DENIED);
        assertThat(policy.decide(ACTOR, COMMAND, submission(), null, grant(), context(), journey(), consent(), null))
                .isEqualTo(SignalCommandPolicy.Decision.DENIED);

        assertInvalidSubmission(submission(null, ANCHOR, QuickSignalValue.QUEUE_OVER_30,
                GENERATION, CONTEXT, REVISION));
        assertInvalidSubmission(submission(JOURNEY, nil, QuickSignalValue.QUEUE_OVER_30,
                GENERATION, CONTEXT, REVISION));
        assertInvalidSubmission(submission(JOURNEY, ANCHOR, null, GENERATION, CONTEXT, REVISION));
        assertInvalidSubmission(submission(JOURNEY, ANCHOR, QuickSignalValue.QUEUE_OVER_30,
                -1, CONTEXT, REVISION));
        assertInvalidSubmission(submission(JOURNEY, ANCHOR, QuickSignalValue.QUEUE_OVER_30,
                GENERATION, nil, REVISION));
        assertInvalidSubmission(submission(JOURNEY, ANCHOR, QuickSignalValue.QUEUE_OVER_30,
                GENERATION, CONTEXT, -1));
    }

    @Test void submissionDiagnosticsRedactItsEntireFingerprint() {
        assertThat(submission().toString()).isEqualTo("SubmissionFingerprint[private]")
                .doesNotContain(JOURNEY.toString(), ANCHOR.toString(), CONTEXT.toString(),
                        "QUEUE_OVER_30", Long.toString(GENERATION), Long.toString(REVISION));
    }

    private SignalCommandPolicy.Decision decide(
            QuickSignalReceipt receipt,
            SignalCommandGrant grant,
            LiveRouteContext context,
            Journey journey,
            PresenceConsent consent,
            Instant now) {
        return policy.decide(ACTOR, COMMAND, submission(), receipt, grant, context, journey, consent, now);
    }

    private void assertConflict(
            QuickSignalReceipt receipt, SignalCommandPolicy.SubmissionFingerprint submission) {
        assertThat(policy.decide(ACTOR, COMMAND, submission, receipt, null,
                null, journey(), null, NOW)).isEqualTo(SignalCommandPolicy.Decision.CONFLICT);
    }

    private void assertDenied(
            QuickSignalReceipt receipt,
            SignalCommandGrant grant,
            LiveRouteContext context,
            Journey journey,
            PresenceConsent consent,
            Instant now) {
        assertThat(decide(receipt, grant, context, journey, consent, now))
                .isEqualTo(SignalCommandPolicy.Decision.DENIED);
    }

    private void assertSubmissionDenied(SignalCommandPolicy.SubmissionFingerprint submission) {
        assertThat(policy.decide(ACTOR, COMMAND, submission, null, grant(),
                context(), journey(), consent(), NOW)).isEqualTo(SignalCommandPolicy.Decision.DENIED);
    }

    private void assertInvalidSubmission(SignalCommandPolicy.SubmissionFingerprint submission) {
        assertThat(policy.decide(ACTOR, COMMAND, submission, null, grant(),
                context(), journey(), consent(), NOW)).isEqualTo(SignalCommandPolicy.Decision.DENIED);
    }

    private static SignalCommandGrant grant() {
        return new SignalCommandGrant(COMMAND, admission(ACTOR), SignalCommandGrant.State.UNUSED);
    }

    private static SignalAdmission admission(UUID actorId) {
        return new SignalAdmission(actorId, JOURNEY, CONTEXT, ANCHOR, REVISION, GENERATION,
                Set.of(QuickSignalValue.Category.QUEUE), ISSUED, ISSUED.plusSeconds(90));
    }

    private static LiveRouteContext context() {
        return new LiveRouteContext(CONTEXT, ACTOR, JOURNEY, REVISION, Set.of(ANCHOR));
    }

    private static Journey journey() {
        return Journey.start(JOURNEY, ACTOR, Journey.Kind.TRIP, STARTED);
    }

    private static PresenceConsent consent() {
        return new PresenceConsent(ACTOR, JOURNEY, GENERATION, true, true);
    }

    private static QuickSignalReceipt receipt(Instant retainUntil) {
        return receipt(RECEIVED, RECEIVED.plusSeconds(20), retainUntil);
    }

    private static QuickSignalReceipt receipt(
            Instant receivedAt, Instant expiresAt, Instant retainUntil) {
        var signal = new QuickSignal(COMMAND, ACTOR, JOURNEY, ANCHOR,
                QuickSignalValue.QUEUE_OVER_30, GENERATION, receivedAt, expiresAt);
        return new QuickSignalReceipt(signal, CONTEXT, REVISION, retainUntil,
                QuickSignalReceipt.State.ACTIVE);
    }

    private static SignalCommandPolicy.SubmissionFingerprint submission() {
        return submission(JOURNEY, ANCHOR, QuickSignalValue.QUEUE_OVER_30,
                GENERATION, CONTEXT, REVISION);
    }

    private static SignalCommandPolicy.SubmissionFingerprint submission(
            UUID journeyId,
            UUID anchorId,
            QuickSignalValue value,
            long generation,
            UUID contextId,
            long revision) {
        return new SignalCommandPolicy.SubmissionFingerprint(
                journeyId, anchorId, value, generation, contextId, revision);
    }

}
