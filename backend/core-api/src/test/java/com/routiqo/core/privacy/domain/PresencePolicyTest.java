package com.routiqo.core.privacy.domain;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class PresencePolicyTest {
 private final Instant now=Instant.parse("2026-09-06T00:00:00Z");
 private PresencePolicy.Entry visible(UUID id) { return new PresencePolicy.Entry(id,now.plusSeconds(60),false,true); }
 @Test void suppressesSparsePresenceAndDeduplicatesActors() {
   var policy=new PresencePolicy(3); var actor=UUID.randomUUID();
   assertThat(policy.aggregate(List.of(visible(actor),visible(actor),visible(UUID.randomUUID())),now)).isEmpty();
   assertThat(policy.aggregate(List.of(visible(actor),visible(UUID.randomUUID()),visible(UUID.randomUUID())),now)).hasValue(3);
 }
 @Test void ghostModeWinsOverStaleDiscoverableEntry() {
   var actor=UUID.randomUUID();
   var rows=List.of(visible(actor),new PresencePolicy.Entry(actor,now.plusSeconds(60),true,true),visible(UUID.randomUUID()));
   assertThat(new PresencePolicy(2).aggregate(rows,now)).isEmpty();
 }
 @Test void expiredAndCompletedJourneysAreExcluded() {
   var rows=List.of(visible(UUID.randomUUID()),new PresencePolicy.Entry(UUID.randomUUID(),now,false,true),new PresencePolicy.Entry(UUID.randomUUID(),now.plusSeconds(60),false,false));
   assertThat(new PresencePolicy(2).aggregate(rows,now)).isEmpty();
 }
 @Test void cannotConfigureIndividualVisibility() { assertThatThrownBy(()->new PresencePolicy(1)).isInstanceOf(IllegalArgumentException.class); }
}

