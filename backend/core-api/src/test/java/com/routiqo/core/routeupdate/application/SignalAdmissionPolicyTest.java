package com.routiqo.core.routeupdate.application;

import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.privacy.domain.PresenceConsent;
import com.routiqo.core.routeupdate.domain.LiveRouteContext;
import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.SignalAdmission;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignalAdmissionPolicyTest {
    private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID JOURNEY = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID CONTEXT = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID ANCHOR = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final Instant STARTED = Instant.parse("2026-09-12T12:00:00Z");
    private static final Instant ISSUED = STARTED.plusSeconds(60);
    private static final Instant NOW = ISSUED.plusSeconds(30);
    private static final long REVISION = 7;
    private static final long GENERATION = 3;

    private final SignalAdmissionPolicy policy = new SignalAdmissionPolicy();

    @Test void allowsAnotherValueInTheAdmittedCategoryUsingAuthoritativeSnapshots() {
        assertThat(policy.permits(ACTOR,
                admission(ACTOR, JOURNEY, CONTEXT, ANCHOR, REVISION, GENERATION,
                        Set.of(QuickSignalValue.Category.QUEUE), ISSUED, ISSUED.plusSeconds(90)),
                context(CONTEXT, ACTOR, JOURNEY, REVISION, Set.of(ANCHOR)),
                Journey.start(JOURNEY, ACTOR, Journey.Kind.TRIP, STARTED),
                consent(ACTOR, JOURNEY, GENERATION, true, true),
                QuickSignalValue.QUEUE_OVER_30, NOW)).isTrue();
    }

    @Test void anyMissingAuthoritativeContextFailsClosed() {
        var admission = admission();
        var context = context();
        var journey = journey();
        var consent = consent();
        assertThat(policy.permits(null, admission, context, journey, consent,
                QuickSignalValue.QUEUE_UNDER_5, NOW)).isFalse();
        assertThat(policy.permits(ACTOR, null, context, journey, consent,
                QuickSignalValue.QUEUE_UNDER_5, NOW)).isFalse();
        assertThat(policy.permits(ACTOR, admission, null, journey, consent,
                QuickSignalValue.QUEUE_UNDER_5, NOW)).isFalse();
        assertThat(policy.permits(ACTOR, admission, context, null, consent,
                QuickSignalValue.QUEUE_UNDER_5, NOW)).isFalse();
        assertThat(policy.permits(ACTOR, admission, context, journey, null,
                QuickSignalValue.QUEUE_UNDER_5, NOW)).isFalse();
        assertThat(policy.permits(ACTOR, admission, context, journey, consent, null, NOW)).isFalse();
        assertThat(policy.permits(ACTOR, admission, context, journey, consent,
                QuickSignalValue.QUEUE_UNDER_5, null)).isFalse();
    }

    @Test void admissionAndJourneyTimesAreHalfOpenAndFailClosedAtExtremes() {
        var context = context();
        var journey = journey();
        var consent = consent();
        var admission = admission();
        assertThat(permits(admission, context, journey, consent, ISSUED.minusNanos(1))).isFalse();
        assertThat(permits(admission, context, journey, consent, ISSUED)).isTrue();
        assertThat(permits(admission, context, journey, consent, admission.expiresAt().minusNanos(1))).isTrue();
        assertThat(permits(admission, context, journey, consent, admission.expiresAt())).isFalse();
        assertThat(permits(admission, context, journey, consent, Instant.MIN)).isFalse();
        assertThat(permits(admission, context, journey, consent, Instant.MAX)).isFalse();

        var futureJourney = Journey.start(JOURNEY, ACTOR, Journey.Kind.TRIP, NOW.plusNanos(1));
        assertThat(permits(admission, context, futureJourney, consent, NOW)).isFalse();
        var issuedBeforeStart = admission(ACTOR, JOURNEY, CONTEXT, ANCHOR, REVISION, GENERATION,
                Set.of(QuickSignalValue.Category.QUEUE), STARTED.minusNanos(1), STARTED.plusSeconds(1));
        assertThat(permits(issuedBeforeStart, context, journey, consent, STARTED)).isFalse();
        var issuedAtStart = admission(ACTOR, JOURNEY, CONTEXT, ANCHOR, REVISION, GENERATION,
                Set.of(QuickSignalValue.Category.QUEUE), STARTED, STARTED.plusSeconds(1));
        assertThat(permits(issuedAtStart, context, journey, consent, STARTED)).isTrue();
    }

    @Test void everyActorAndJourneyRelationshipMustMatch() {
        var other = UUID.randomUUID();
        assertThat(policy.permits(other, admission(), context(), journey(), consent(),
                QuickSignalValue.QUEUE_UNDER_5, NOW)).isFalse();
        assertDenied(admission(other, JOURNEY, CONTEXT, ANCHOR, REVISION, GENERATION,
                categories(), ISSUED, ISSUED.plusSeconds(90)), context(), journey(), consent());
        assertDenied(admission(), context(CONTEXT, other, JOURNEY, REVISION, Set.of(ANCHOR)), journey(), consent());
        assertDenied(admission(), context(), Journey.start(JOURNEY, other, Journey.Kind.TRIP, STARTED), consent());
        assertDenied(admission(), context(), journey(), consent(other, JOURNEY, GENERATION, true, true));

        assertDenied(admission(ACTOR, other, CONTEXT, ANCHOR, REVISION, GENERATION,
                categories(), ISSUED, ISSUED.plusSeconds(90)), context(), journey(), consent());
        assertDenied(admission(), context(CONTEXT, ACTOR, other, REVISION, Set.of(ANCHOR)), journey(), consent());
        assertDenied(admission(), context(), Journey.start(other, ACTOR, Journey.Kind.TRIP, STARTED), consent());
        assertDenied(admission(), context(), journey(), consent(ACTOR, other, GENERATION, true, true));
    }

    @Test void contextRevisionAnchorCategoryAndConsentGenerationMustMatch() {
        assertDenied(admission(ACTOR, JOURNEY, UUID.randomUUID(), ANCHOR, REVISION, GENERATION,
                categories(), ISSUED, ISSUED.plusSeconds(90)), context(), journey(), consent());
        assertDenied(admission(ACTOR, JOURNEY, CONTEXT, ANCHOR, REVISION + 1, GENERATION,
                categories(), ISSUED, ISSUED.plusSeconds(90)), context(), journey(), consent());
        assertDenied(admission(ACTOR, JOURNEY, CONTEXT, UUID.randomUUID(), REVISION, GENERATION,
                categories(), ISSUED, ISSUED.plusSeconds(90)), context(), journey(), consent());
        assertDenied(admission(ACTOR, JOURNEY, CONTEXT, ANCHOR, REVISION, GENERATION,
                Set.of(QuickSignalValue.Category.TRAFFIC), ISSUED, ISSUED.plusSeconds(90)),
                context(), journey(), consent());
        assertDenied(admission(ACTOR, JOURNEY, CONTEXT, ANCHOR, REVISION, GENERATION + 1,
                categories(), ISSUED, ISSUED.plusSeconds(90)), context(), journey(), consent());
        assertDenied(admission(), context(CONTEXT, ACTOR, JOURNEY, REVISION + 1, Set.of(ANCHOR)),
                journey(), consent());
        assertDenied(admission(), context(CONTEXT, ACTOR, JOURNEY, REVISION, Set.of(UUID.randomUUID())),
                journey(), consent());
    }

    @Test void ghostCompletionAndReenableNeverReviveAnEarlierAdmission() {
        var journey = journey();
        var context = context();
        var visible = consent(ACTOR, JOURNEY, GENERATION, true, true);
        var admission = admission();
        assertThat(permits(admission, context, journey, visible, NOW)).isTrue();

        var ghost = visible.changeSharing(false);
        assertThat(permits(admission, context, journey, ghost, NOW)).isFalse();
        var resumed = ghost.changeSharing(true);
        assertThat(resumed.generation()).isGreaterThan(admission.consentGeneration());
        assertThat(permits(admission, context, journey, resumed, NOW)).isFalse();

        var completedConsent = visible.endJourney();
        assertThat(permits(admission, context, journey, completedConsent, NOW)).isFalse();
        var completedJourney = journey.complete(ACTOR, NOW);
        assertThat(permits(admission, context, completedJourney, visible, NOW)).isFalse();
    }

    @Test void routeContextCopiesAndBoundsItsPrivateAnchorSet() {
        var anchors = new HashSet<>(Set.of(ANCHOR));
        var context = context(CONTEXT, ACTOR, JOURNEY, REVISION, anchors);
        anchors.add(UUID.randomUUID());
        assertThat(context.anchorIds()).containsExactly(ANCHOR);
        assertThatThrownBy(() -> context.anchorIds().add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);

        assertInvalidContext(null, ACTOR, JOURNEY, 0, Set.of(ANCHOR));
        assertInvalidContext(new UUID(0, 0), ACTOR, JOURNEY, 0, Set.of(ANCHOR));
        assertInvalidContext(CONTEXT, null, JOURNEY, 0, Set.of(ANCHOR));
        assertInvalidContext(CONTEXT, ACTOR, null, 0, Set.of(ANCHOR));
        assertInvalidContext(CONTEXT, ACTOR, JOURNEY, -1, Set.of(ANCHOR));
        assertInvalidContext(CONTEXT, ACTOR, JOURNEY, 0, null);
        assertInvalidContext(CONTEXT, ACTOR, JOURNEY, 0, Set.of());
        var withNull = new HashSet<UUID>();
        withNull.add(null);
        assertInvalidContext(CONTEXT, ACTOR, JOURNEY, 0, withNull);
        var withNil = new HashSet<UUID>();
        withNil.add(new UUID(0, 0));
        assertInvalidContext(CONTEXT, ACTOR, JOURNEY, 0, withNil);
        var tooMany = new HashSet<UUID>();
        while (tooMany.size() < 129) tooMany.add(UUID.randomUUID());
        assertInvalidContext(CONTEXT, ACTOR, JOURNEY, 0, tooMany);
    }

    @Test void admissionCopiesAndBoundsItsPrivateCategoriesAndLifetime() {
        var mutable = new HashSet<>(Set.of(QuickSignalValue.Category.QUEUE));
        var admission = admission(ACTOR, JOURNEY, CONTEXT, ANCHOR, REVISION, GENERATION,
                mutable, ISSUED, ISSUED.plusSeconds(90));
        mutable.add(QuickSignalValue.Category.TRAFFIC);
        assertThat(admission.permittedCategories()).containsExactly(QuickSignalValue.Category.QUEUE);
        assertThatThrownBy(() -> admission.permittedCategories().add(QuickSignalValue.Category.TRAFFIC))
                .isInstanceOf(UnsupportedOperationException.class);

        UUID nil = new UUID(0, 0);
        assertInvalidAdmission(null, JOURNEY, CONTEXT, ANCHOR, 0, 0, categories(), ISSUED, NOW);
        assertInvalidAdmission(nil, JOURNEY, CONTEXT, ANCHOR, 0, 0, categories(), ISSUED, NOW);
        assertInvalidAdmission(ACTOR, null, CONTEXT, ANCHOR, 0, 0, categories(), ISSUED, NOW);
        assertInvalidAdmission(ACTOR, JOURNEY, null, ANCHOR, 0, 0, categories(), ISSUED, NOW);
        assertInvalidAdmission(ACTOR, JOURNEY, CONTEXT, null, 0, 0, categories(), ISSUED, NOW);
        assertInvalidAdmission(ACTOR, JOURNEY, CONTEXT, nil, 0, 0, categories(), ISSUED, NOW);
        assertInvalidAdmission(ACTOR, JOURNEY, CONTEXT, ANCHOR, -1, 0, categories(), ISSUED, NOW);
        assertInvalidAdmission(ACTOR, JOURNEY, CONTEXT, ANCHOR, 0, -1, categories(), ISSUED, NOW);
        assertInvalidAdmission(ACTOR, JOURNEY, CONTEXT, ANCHOR, 0, 0, null, ISSUED, NOW);
        assertInvalidAdmission(ACTOR, JOURNEY, CONTEXT, ANCHOR, 0, 0, Set.of(), ISSUED, NOW);
        var nullCategory = new HashSet<QuickSignalValue.Category>();
        nullCategory.add(null);
        assertInvalidAdmission(ACTOR, JOURNEY, CONTEXT, ANCHOR, 0, 0, nullCategory, ISSUED, NOW);
        assertInvalidAdmission(ACTOR, JOURNEY, CONTEXT, ANCHOR, 0, 0, categories(), null, NOW);
        assertInvalidAdmission(ACTOR, JOURNEY, CONTEXT, ANCHOR, 0, 0, categories(), ISSUED, null);
        assertInvalidAdmission(ACTOR, JOURNEY, CONTEXT, ANCHOR, 0, 0, categories(), ISSUED, ISSUED);
        assertInvalidAdmission(ACTOR, JOURNEY, CONTEXT, ANCHOR, 0, 0, categories(), ISSUED, ISSUED.minusNanos(1));
        assertInvalidAdmission(ACTOR, JOURNEY, CONTEXT, ANCHOR, 0, 0, categories(),
                ISSUED, ISSUED.plusSeconds(90).plusNanos(1));
        assertInvalidAdmission(ACTOR, JOURNEY, CONTEXT, ANCHOR, 0, 0, categories(), Instant.MIN, Instant.MAX);
        assertInvalidAdmission(ACTOR, JOURNEY, CONTEXT, ANCHOR, 0, 0, categories(), Instant.MAX, Instant.MIN);
        assertThat(admission(ACTOR, JOURNEY, CONTEXT, ANCHOR, Long.MAX_VALUE, Long.MAX_VALUE,
                Set.of(QuickSignalValue.Category.values()), ISSUED, ISSUED.plusSeconds(90)))
                .isNotNull();
    }

    @Test void diagnosticsRedactAllAdmissionAndContextMetadata() {
        var context = context();
        var admission = admission();
        assertThat(context.toString()).isEqualTo("LiveRouteContext[private]")
                .doesNotContain(CONTEXT.toString(), ACTOR.toString(), JOURNEY.toString(), ANCHOR.toString(),
                        Long.toString(REVISION));
        assertThat(admission.toString()).isEqualTo("SignalAdmission[private]")
                .doesNotContain(CONTEXT.toString(), ACTOR.toString(), JOURNEY.toString(), ANCHOR.toString(),
                        Long.toString(REVISION), Long.toString(GENERATION), "QUEUE",
                        ISSUED.toString(), admission.expiresAt().toString());
    }

    private boolean permits(SignalAdmission admission, LiveRouteContext context, Journey journey,
            PresenceConsent consent, Instant now) {
        return policy.permits(ACTOR, admission, context, journey, consent,
                QuickSignalValue.QUEUE_15_TO_30, now);
    }

    private void assertDenied(SignalAdmission admission, LiveRouteContext context,
            Journey journey, PresenceConsent consent) {
        assertThat(permits(admission, context, journey, consent, NOW)).isFalse();
    }

    private static SignalAdmission admission() {
        return admission(ACTOR, JOURNEY, CONTEXT, ANCHOR, REVISION, GENERATION,
                categories(), ISSUED, ISSUED.plusSeconds(90));
    }

    private static SignalAdmission admission(UUID actor, UUID journey, UUID context, UUID anchor,
            long revision, long generation, Set<QuickSignalValue.Category> categories,
            Instant issuedAt, Instant expiresAt) {
        return new SignalAdmission(actor, journey, context, anchor, revision, generation,
                categories, issuedAt, expiresAt);
    }

    private static LiveRouteContext context() {
        return context(CONTEXT, ACTOR, JOURNEY, REVISION, Set.of(ANCHOR));
    }

    private static LiveRouteContext context(UUID context, UUID actor, UUID journey,
            long revision, Set<UUID> anchors) {
        return new LiveRouteContext(context, actor, journey, revision, anchors);
    }

    private static Journey journey() {
        return Journey.start(JOURNEY, ACTOR, Journey.Kind.TRIP, STARTED);
    }

    private static PresenceConsent consent() {
        return consent(ACTOR, JOURNEY, GENERATION, true, true);
    }

    private static PresenceConsent consent(UUID actor, UUID journey, long generation,
            boolean sharing, boolean journeyActive) {
        return new PresenceConsent(actor, journey, generation, sharing, journeyActive);
    }

    private static Set<QuickSignalValue.Category> categories() {
        return Set.of(QuickSignalValue.Category.QUEUE);
    }

    private static void assertInvalidContext(UUID context, UUID actor, UUID journey,
            long revision, Set<UUID> anchors) {
        assertThatThrownBy(() -> context(context, actor, journey, revision, anchors))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid live route context")
                .hasNoCause();
    }

    private static void assertInvalidAdmission(UUID actor, UUID journey, UUID context, UUID anchor,
            long revision, long generation, Set<QuickSignalValue.Category> categories,
            Instant issuedAt, Instant expiresAt) {
        assertThatThrownBy(() -> admission(actor, journey, context, anchor, revision, generation,
                categories, issuedAt, expiresAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid signal admission")
                .hasNoCause();
    }
}
