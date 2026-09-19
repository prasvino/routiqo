package com.routiqo.core.routeupdate.domain;

import java.util.Optional;
import java.util.UUID;

/** Internal terminal result for one known private signal command. */
public record SignalCommandStopResult(
        UUID commandId,
        Optional<QuickSignalReceipt> receipt) {
    private static final UUID NIL_ID = new UUID(0, 0);

    public SignalCommandStopResult {
        if (commandId == null || NIL_ID.equals(commandId) || receipt == null
                || receipt.isPresent() && (!commandId.equals(
                        receipt.get().signal().signalId())
                        || receipt.get().state() == QuickSignalReceipt.State.ACTIVE)) {
            throw new IllegalArgumentException("Invalid signal command stop result");
        }
    }

    @Override public String toString() { return "SignalCommandStopResult[private]"; }
}
