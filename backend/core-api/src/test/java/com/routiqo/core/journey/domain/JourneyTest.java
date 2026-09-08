package com.routiqo.core.journey.domain;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class JourneyTest {
 @Test void onlyOwnerCanCompleteAndRetryIsIdempotent() {
   var actor=UUID.randomUUID(); var start=Instant.parse("2026-09-06T00:00:00Z");
   var journey=Journey.start(UUID.randomUUID(),actor,Journey.Kind.COMMUTE,start);
   assertThatThrownBy(()->journey.complete(UUID.randomUUID(),start.plusSeconds(60))).isInstanceOf(SecurityException.class);
   var completed=journey.complete(actor,start.plusSeconds(60));
   assertThat(completed.status()).isEqualTo(Journey.Status.COMPLETED);
   assertThat(completed.complete(actor,start.plusSeconds(120))).isSameAs(completed);
 }
 @Test void completionCannotPrecedeStart() {
   var actor=UUID.randomUUID(); var now=Instant.now();
   var journey=Journey.start(UUID.randomUUID(),actor,Journey.Kind.TRIP,now);
   assertThatThrownBy(()->journey.complete(actor,now.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
 }
}

