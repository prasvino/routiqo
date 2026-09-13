package com.routiqo.core.routeupdate.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredLiveRouteContextTest {
    private static final Instant NOW = Instant.parse("2026-09-13T08:00:00Z");

    @Test
    void lifetimeIsPositiveBoundedHalfOpenAndRedacted() {
        LiveRouteContext context = context();
        StoredLiveRouteContext stored = new StoredLiveRouteContext(
                context, NOW, NOW.plus(Duration.ofHours(24)));

        assertThat(stored.isCurrentAt(null)).isFalse();
        assertThat(stored.isCurrentAt(NOW.minusNanos(1))).isFalse();
        assertThat(stored.isCurrentAt(NOW)).isTrue();
        assertThat(stored.isCurrentAt(stored.expiresAt().minusNanos(1))).isTrue();
        assertThat(stored.isCurrentAt(stored.expiresAt())).isFalse();
        assertThat(stored.toString()).isEqualTo("StoredLiveRouteContext[private]")
                .doesNotContain(context.contextId().toString());
    }

    @Test
    void rejectsNullNonPositiveOversizeAndExtremeLifetimesWithoutDetails() {
        LiveRouteContext context = context();
        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable invalid : java.util.List
                .<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                        () -> new StoredLiveRouteContext(null, NOW, NOW.plusSeconds(1)),
                        () -> new StoredLiveRouteContext(context, null, NOW.plusSeconds(1)),
                        () -> new StoredLiveRouteContext(context, NOW, null),
                        () -> new StoredLiveRouteContext(context, NOW, NOW),
                        () -> new StoredLiveRouteContext(context, NOW, NOW.minusNanos(1)),
                        () -> new StoredLiveRouteContext(
                                context, NOW, NOW.plus(Duration.ofHours(24)).plusNanos(1)),
                        () -> new StoredLiveRouteContext(context, Instant.MIN, Instant.MAX))) {
            assertThatThrownBy(invalid)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid stored live route context")
                    .hasNoCause();
        }
    }

    private static LiveRouteContext context() {
        return new LiveRouteContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
                Set.of(UUID.randomUUID()));
    }
}
