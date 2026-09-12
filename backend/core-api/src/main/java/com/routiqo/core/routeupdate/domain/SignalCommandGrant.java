package com.routiqo.core.routeupdate.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal server-issued command state bound to one admission. It is not a public credential, and
 * construction alone does not authorize signal acceptance.
 */
public record SignalCommandGrant(UUID commandId, SignalAdmission admission, State state) {
    private static final UUID NIL_ID = new UUID(0, 0);

    public enum State {
        UNUSED,
        CONSUMED
    }

    public SignalCommandGrant {
        if (commandId == null || NIL_ID.equals(commandId) || admission == null || state == null) {
            throw new IllegalArgumentException("Invalid signal command grant");
        }
    }

    /** Temporal and consumption state only; current authority must be checked separately. */
    public boolean isUnusedAt(Instant now) {
        return state == State.UNUSED && admission.isCurrent(now);
    }

    public SignalCommandGrant consume() {
        if (state == State.CONSUMED) {
            return this;
        }
        return new SignalCommandGrant(commandId, admission, State.CONSUMED);
    }

    @Override
    public String toString() {
        return "SignalCommandGrant[private]";
    }
}
