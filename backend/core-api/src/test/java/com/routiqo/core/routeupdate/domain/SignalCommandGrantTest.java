package com.routiqo.core.routeupdate.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignalCommandGrantTest {
    private static final UUID COMMAND = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID JOURNEY = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID CONTEXT = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID ANCHOR = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final Instant ISSUED = Instant.parse("2026-09-12T12:00:00Z");

    @Test void reusesAdmissionLifetimeAndConsumptionIsTerminalWithoutRenewal() {
        var admission = admission();
        var unused = new SignalCommandGrant(COMMAND, admission, SignalCommandGrant.State.UNUSED);

        assertThat(unused.isUnusedAt(null)).isFalse();
        assertThat(unused.isUnusedAt(ISSUED.minusNanos(1))).isFalse();
        assertThat(unused.isUnusedAt(ISSUED)).isTrue();
        assertThat(unused.isUnusedAt(admission.expiresAt().minusNanos(1))).isTrue();
        assertThat(unused.isUnusedAt(admission.expiresAt())).isFalse();

        var consumed = unused.consume();
        assertThat(consumed.state()).isEqualTo(SignalCommandGrant.State.CONSUMED);
        assertThat(consumed.commandId()).isEqualTo(COMMAND);
        assertThat(consumed.admission()).isSameAs(admission);
        assertThat(consumed.isUnusedAt(ISSUED)).isFalse();
        assertThat(consumed.consume()).isSameAs(consumed);
    }

    @Test void constructionRejectsInvalidStateWithGenericCauseFreeDiagnostics() {
        assertInvalid(null, admission(), SignalCommandGrant.State.UNUSED);
        assertInvalid(new UUID(0, 0), admission(), SignalCommandGrant.State.UNUSED);
        assertInvalid(COMMAND, null, SignalCommandGrant.State.UNUSED);
        assertInvalid(COMMAND, admission(), null);
    }

    @Test void stringRepresentationRedactsCommandAndAdmissionMetadata() {
        var grant = new SignalCommandGrant(COMMAND, admission(), SignalCommandGrant.State.CONSUMED);

        assertThat(grant.toString()).isEqualTo("SignalCommandGrant[private]")
                .doesNotContain(COMMAND.toString(), ACTOR.toString(), JOURNEY.toString(),
                        CONTEXT.toString(), ANCHOR.toString(), ISSUED.toString(), "CONSUMED");
    }

    private static SignalAdmission admission() {
        return new SignalAdmission(ACTOR, JOURNEY, CONTEXT, ANCHOR, 4, 7,
                Set.of(QuickSignalValue.Category.QUEUE), ISSUED, ISSUED.plusSeconds(90));
    }

    private static void assertInvalid(
            UUID commandId, SignalAdmission admission, SignalCommandGrant.State state) {
        assertThatThrownBy(() -> new SignalCommandGrant(commandId, admission, state))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid signal command grant")
                .hasNoCause();
    }
}
