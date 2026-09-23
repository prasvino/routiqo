package com.routiqo.core.publiclive.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Durable, private candidate store; implementations require the caller's authority transaction. */
public interface PublicSignalIntentStore {
    enum State { ACTIVE, STOPPED }

    record Intent(UUID actorId, UUID commandId, UUID journeyId, UUID personRef,
            long verificationRevision, long restrictionRevision,
            UUID anchorId, String category, String value,
            Instant windowStart, Instant receivedAt, Instant evidenceExpiresAt,
            Instant sharedAt, UUID shareRequestId, State state) {
        @Override public String toString() { return "PublicSignalIntent[private]"; }
    }

    record Cursor(Instant sharedAt, UUID commandId) {}
    record Handle(UUID journeyId, UUID commandId, State state, Instant sharedAt) {
        @Override public String toString() { return "PublicSignalHandle[private]"; }
    }
    record HandlePage(List<Handle> handles, Cursor nextCursor) {
        public HandlePage { handles = List.copyOf(handles); }
        @Override public String toString() { return "PublicSignalHandlePage[private]"; }
    }

    Intent share(Intent intent);
    State stop(UUID actorId, UUID journeyId, UUID commandId, Instant now);
    HandlePage list(UUID actorId, Instant since, Cursor cursor);
}
