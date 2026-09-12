package com.routiqo.core.privacy.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PresenceConsentTest {
    private final Instant now = Instant.parse("2026-09-12T01:00:00Z");
    private PresenceConsent initial() { return PresenceConsent.initial(UUID.randomUUID(), UUID.randomUUID()); }
    @Test void defaultsPrivateAndGhostModeInvalidatesOldHeartbeatsEvenAfterReenable() {
        var initial = initial();
        assertThatThrownBy(() -> initial.issueLease(0, now)).isInstanceOf(IllegalStateException.class);
        var visible = initial.changeSharing(true);
        var lease = visible.issueLease(visible.generation(), now);
        var ghost = visible.changeSharing(false);
        assertThat(ghost.permits(lease, now)).isFalse();
        var resumed = ghost.changeSharing(true);
        assertThat(resumed.permits(lease, now)).isFalse();
        assertThatThrownBy(() -> resumed.issueLease(visible.generation(), now)).isInstanceOf(IllegalStateException.class);
        assertThat(resumed.permits(resumed.issueLease(resumed.generation(), now), now)).isTrue();
    }
    @Test void completionIsTerminalAndRepeatedCommandsDoNotChangeGeneration() {
        var visible = initial().changeSharing(true);
        assertThat(visible.changeSharing(true)).isEqualTo(visible);
        var ended = visible.endJourney();
        assertThat(ended.endJourney()).isEqualTo(ended);
        assertThat(ended.permits(visible.issueLease(visible.generation(), now), now)).isFalse();
        assertThatThrownBy(() -> ended.changeSharing(true)).isInstanceOf(IllegalStateException.class);
    }
    @Test void leaseIsBoundToOwnerJourneyTimeAndMaximumLifetime() {
        var visible = initial().changeSharing(true);
        var lease = visible.issueLease(visible.generation(), now);
        assertThat(visible.permits(lease, now.minusSeconds(1))).isFalse();
        assertThat(visible.permits(lease, now.plusSeconds(89))).isTrue();
        assertThat(visible.permits(lease, now.plusSeconds(90))).isFalse();
        assertThat(initial().changeSharing(true).permits(lease, now)).isFalse();
        var otherJourney = new PresenceConsent(visible.actorId(), UUID.randomUUID(), visible.generation(), true, true);
        assertThat(otherJourney.permits(lease, now)).isFalse();
        assertThatThrownBy(() -> new PresenceConsent.Lease(visible.actorId(), visible.journeyId(), 1, now, now.plusSeconds(91)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void generationCannotWrapAndDiagnosticStringsExcludeIdentity() {
        var state = new PresenceConsent(UUID.randomUUID(), UUID.randomUUID(), Long.MAX_VALUE, false, true);
        assertThatThrownBy(() -> state.changeSharing(true)).isInstanceOf(ArithmeticException.class);
        assertThat(state.toString()).doesNotContain(state.actorId().toString(), state.journeyId().toString());
    }
}
