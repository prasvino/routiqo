package com.routiqo.core.routeupdate.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteBindingAttemptTest {
    private static final Instant NOW = Instant.parse("2026-09-13T08:00:00Z");

    @Test
    void lifetimeIsHalfOpenAndConsumptionIsMonotonic() {
        RouteBindingAttempt pending = attempt(NOW, NOW.plusSeconds(90));
        assertThat(pending.isCurrentAt(NOW)).isTrue();
        assertThat(pending.isCurrentAt(NOW.plusSeconds(90))).isFalse();
        assertThat(pending.isCurrentAt(NOW.minusNanos(1))).isFalse();
        RouteBindingAttempt consumed = pending.consume();
        assertThat(consumed.state()).isEqualTo(RouteBindingAttempt.State.CONSUMED);
        assertThat(consumed.consume()).isSameAs(consumed);
        assertThat(consumed.isCurrentAt(NOW.plusSeconds(1))).isFalse();
    }

    @Test
    void rejectsInvalidIdentityGenerationAndTemporalBoundsWithoutLeakingMetadata() {
        UUID nil = new UUID(0, 0);
        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable invalid : java.util.List
                .<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                        () -> new RouteBindingAttempt(nil, UUID.randomUUID(), UUID.randomUUID(), 0,
                                UUID.randomUUID(), Optional.empty(), NOW, NOW.plusSeconds(90),
                                RouteBindingAttempt.State.PENDING),
                        () -> new RouteBindingAttempt(UUID.randomUUID(), UUID.randomUUID(),
                                UUID.randomUUID(), -1, UUID.randomUUID(), Optional.empty(), NOW,
                                NOW.plusSeconds(90), RouteBindingAttempt.State.PENDING),
                        () -> attempt(NOW, NOW),
                        () -> attempt(NOW, NOW.plusSeconds(91)),
                        () -> attempt(Instant.MAX, Instant.MIN))) {
            assertThatThrownBy(invalid).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid route binding attempt").hasNoCause();
        }
        RouteBindingAttempt valid = attempt(NOW, NOW.plusSeconds(90));
        assertThat(valid.toString()).isEqualTo("RouteBindingAttempt[private]")
                .doesNotContain(valid.actorId().toString(), valid.attemptId().toString());
    }

    @Test
    void outcomesRequireContextOnlyForBoundAndRedactIt() {
        StoredLiveRouteContext context = new StoredLiveRouteContext(
                new LiveRouteContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
                        Set.of(UUID.randomUUID())), NOW, NOW.plusSeconds(60));
        assertThat(RouteBindingOutcome.bound(context).status())
                .isEqualTo(RouteBindingOutcome.Status.BOUND);
        assertThat(RouteBindingOutcome.empty(RouteBindingOutcome.Status.NO_ROUTE).context()).isEmpty();
        assertThat(RouteBindingOutcome.bound(context).toString())
                .isEqualTo("RouteBindingOutcome[private]")
                .doesNotContain(context.context().contextId().toString());
        assertThatThrownBy(() -> new RouteBindingOutcome(
                RouteBindingOutcome.Status.NO_ROUTE, Optional.of(context)))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    private static RouteBindingAttempt attempt(Instant issuedAt, Instant deadline) {
        return new RouteBindingAttempt(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
                UUID.randomUUID(), Optional.empty(), issuedAt, deadline,
                RouteBindingAttempt.State.PENDING);
    }
}
