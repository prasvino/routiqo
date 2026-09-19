package com.routiqo.core.routeupdate.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignalCommandStopResultTest {
    private static final UUID COMMAND = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-19T09:00:00Z");

    @Test void permitsOnlyMatchingTerminalReceiptAndRedactsDiagnostics() {
        QuickSignalReceipt terminal = receipt(COMMAND).withdraw();
        var withReceipt = new SignalCommandStopResult(COMMAND, Optional.of(terminal));
        var withoutReceipt = new SignalCommandStopResult(COMMAND, Optional.empty());

        assertThat(withReceipt.receipt()).contains(terminal);
        assertThat(withoutReceipt.receipt()).isEmpty();
        assertThat(withReceipt.toString()).isEqualTo("SignalCommandStopResult[private]");

        assertThatThrownBy(() -> new SignalCommandStopResult(null, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> new SignalCommandStopResult(new UUID(0, 0), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> new SignalCommandStopResult(COMMAND, null))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> new SignalCommandStopResult(
                UUID.randomUUID(), Optional.of(terminal)))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> new SignalCommandStopResult(
                COMMAND, Optional.of(receipt(COMMAND))))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause()
                .hasMessage("Invalid signal command stop result");
    }

    private static QuickSignalReceipt receipt(UUID command) {
        var signal = new QuickSignal(command, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), QuickSignalValue.QUEUE_UNDER_5, 1,
                NOW, NOW.plusSeconds(60));
        return new QuickSignalReceipt(signal, UUID.randomUUID(), 1,
                NOW.plusSeconds(120), QuickSignalReceipt.State.ACTIVE);
    }
}
