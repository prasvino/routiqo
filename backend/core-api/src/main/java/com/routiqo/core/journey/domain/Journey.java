package com.routiqo.core.journey.domain;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
/** Domain lifecycle only; no protected HTTP writes are exposed by the foundation. */
public record Journey(UUID id, UUID ownerId, Kind kind, Status status, Instant startedAt, Instant completedAt) {
    public enum Kind { COMMUTE, TRIP }
    public enum Status { ACTIVE, COMPLETED }
    public Journey {
        Objects.requireNonNull(id); Objects.requireNonNull(ownerId); Objects.requireNonNull(kind);
        Objects.requireNonNull(status); Objects.requireNonNull(startedAt);
        if (status == Status.ACTIVE && completedAt != null) throw new IllegalArgumentException("Active journey cannot have a completion time");
        if (status == Status.COMPLETED && (completedAt == null || completedAt.isBefore(startedAt))) throw new IllegalArgumentException("Invalid completion time");
    }
    public static Journey start(UUID id, UUID actorId, Kind kind, Instant now) {
        return new Journey(id, actorId, kind, Status.ACTIVE, now, null);
    }
    public Journey complete(UUID actorId, Instant now) {
        if (!ownerId.equals(actorId)) throw new SecurityException("Journey access denied");
        if (status == Status.COMPLETED) return this;
        return new Journey(id, ownerId, kind, Status.COMPLETED, startedAt, Objects.requireNonNull(now));
    }
}

