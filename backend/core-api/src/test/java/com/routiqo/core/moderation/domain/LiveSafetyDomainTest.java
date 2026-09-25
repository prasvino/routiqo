package com.routiqo.core.moderation.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveSafetyDomainTest {
    private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID THIRD = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID REFERENCE = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID REQUEST = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    private static final UUID NIL = new UUID(0, 0);

    @Test
    void assessmentDefaultsToDenialAndHasHalfOpenFiniteValidity() {
        var initial = ContributorAssessment.initial(ACTOR);
        assertThat(initial.eligibleAt(NOW)).isFalse();
        var assessed = initial.assess(0, REFERENCE, NOW.plusSeconds(5),
                NOW.plusSeconds(24 * 3600 + 5));
        assertThat(assessed.revision()).isEqualTo(1);
        assertThat(assessed.eligibleAt(NOW)).isFalse();
        assertThat(assessed.eligibleAt(NOW.plusSeconds(5))).isTrue();
        assertThat(assessed.eligibleAt(assessed.expiresAt().minusNanos(1))).isTrue();
        assertThat(assessed.eligibleAt(assessed.expiresAt())).isFalse();
        assertThat(assessed.eligibleAt(null)).isFalse();
        assertThat(assessed.eligibleAt(Instant.MAX)).isFalse();
        assertThatThrownBy(() -> initial.assess(0, NIL, NOW, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> initial.assess(0, REFERENCE, Instant.MIN, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> initial.assess(0, REFERENCE, NOW, Instant.MAX))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> initial.assess(0, REFERENCE, NOW,
                NOW.plusSeconds(24 * 3600).plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @Test
    void suspensionWinsStaleEnableAndUnsuspensionCannotResurrectAssessment() {
        var initial = ContributorAssessment.initial(ACTOR);
        var assessed = initial.assess(0, REFERENCE, NOW, NOW.plusSeconds(60));
        var suspended = assessed.suspend(0);
        assertThat(suspended.revision()).isEqualTo(2);
        assertThat(suspended.eligibleAt(NOW)).isFalse();
        assertDenied(() -> suspended.assess(2, REFERENCE, NOW, NOW.plusSeconds(60)));
        assertDenied(() -> suspended.assess(1, REFERENCE, NOW, NOW.plusSeconds(60)));
        assertDenied(() -> suspended.unsuspend(1));
        var repeated = suspended.suspend(1);
        assertThat(repeated.revision()).isEqualTo(3);
        var reset = repeated.unsuspend(3);
        assertThat(reset.state()).isEqualTo(ContributorAssessment.State.UNASSESSED);
        assertThat(reset.revision()).isEqualTo(4);
        assertThat(reset.eligibleAt(NOW)).isFalse();
        assertThat(reset.assessmentRef()).isNull();
        assertDenied(() -> reset.assess(-1, REFERENCE, NOW, NOW.plusSeconds(60)));
        assertDenied(() -> reset.suspend(5));
        assertDenied(() -> reset.unsuspend(5));
    }

    @Test
    void saturatedAssessmentCanOnlyBecomePermanentlySuspended() {
        var saturated = new ContributorAssessment(ACTOR, Long.MAX_VALUE - 1,
                ContributorAssessment.State.ASSESSED, REFERENCE, NOW, NOW.plusSeconds(60));
        assertDenied(() -> saturated.assess(Long.MAX_VALUE - 1, REFERENCE,
                NOW, NOW.plusSeconds(60)));
        var revoked = saturated.suspend(Long.MAX_VALUE - 1);
        assertThat(revoked.revision()).isEqualTo(Long.MAX_VALUE);
        assertThat(revoked.state()).isEqualTo(ContributorAssessment.State.SUSPENDED);
        assertThat(revoked.suspend(Long.MAX_VALUE)).isSameAs(revoked);
        assertDenied(() -> revoked.unsuspend(Long.MAX_VALUE));
        assertDenied(() -> revoked.assess(Long.MAX_VALUE, REFERENCE, NOW, NOW.plusSeconds(60)));
        assertDenied(() -> saturated.suspend(-1));
        assertThatThrownBy(() -> new ContributorAssessment(ACTOR, Long.MAX_VALUE,
                ContributorAssessment.State.ASSESSED, REFERENCE, NOW, NOW.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        var almostUnsuspended = new ContributorAssessment(ACTOR, Long.MAX_VALUE - 1,
                ContributorAssessment.State.SUSPENDED, null, null, null);
        assertDenied(() -> almostUnsuspended.unsuspend(Long.MAX_VALUE - 1));
    }

    @Test
    void bilateralBlockRequiresFreshExplicitReverseState() {
        var forward = DirectionalBlock.initial(ACTOR, OTHER);
        var reverse = DirectionalBlock.initial(OTHER, ACTOR);
        assertThat(forward.clearWith(reverse)).isTrue();
        assertThat(forward.clearWith(null)).isFalse();
        var blocked = forward.block(0);
        assertThat(blocked.clearWith(reverse)).isFalse();
        assertThat(reverse.clearWith(blocked)).isFalse();
        assertThat(blocked.block(0).revision()).isEqualTo(2);
        assertDenied(() -> blocked.unblock(0));
        assertThat(blocked.unblock(1).clearWith(reverse)).isTrue();
        assertDenied(() -> blocked.block(2));
        assertDenied(() -> blocked.unblock(-1));
        assertThatThrownBy(() -> forward.clearWith(DirectionalBlock.initial(THIRD, ACTOR)))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> DirectionalBlock.initial(ACTOR, ACTOR))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> DirectionalBlock.initial(ACTOR, NIL))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> DirectionalBlock.initial(null, OTHER))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @Test
    void saturatedBlockCannotBeClearedAndRevocationStillWins() {
        var almost = new DirectionalBlock(ACTOR, OTHER, Long.MAX_VALUE - 1, false);
        var saturated = almost.block(Long.MAX_VALUE - 1);
        assertThat(saturated.block(0)).isSameAs(saturated);
        assertDenied(() -> saturated.unblock(Long.MAX_VALUE));
        assertDenied(() -> almost.unblock(Long.MAX_VALUE - 1));
        assertThat(saturated.clearWith(DirectionalBlock.initial(OTHER, ACTOR))).isFalse();
        assertThatThrownBy(() -> new DirectionalBlock(ACTOR, OTHER, Long.MAX_VALUE, false))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @Test
    void diagnosticsRedactPrivateIdentitiesAndTimes() {
        var assessment = ContributorAssessment.initial(ACTOR)
                .assess(0, REFERENCE, NOW, NOW.plusSeconds(60));
        var block = DirectionalBlock.initial(ACTOR, OTHER).block(0);
        for (Object value : new Object[] { assessment, block }) {
            assertThat(value.toString()).contains("[private]")
                    .doesNotContain(ACTOR.toString(), OTHER.toString(), THIRD.toString(),
                            REFERENCE.toString(), REQUEST.toString(), NOW.toString());
        }
    }

    private static void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable command) {
        assertThatThrownBy(command).isInstanceOf(IllegalStateException.class).hasNoCause();
    }
}
