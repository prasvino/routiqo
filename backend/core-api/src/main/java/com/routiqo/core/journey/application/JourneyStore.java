package com.routiqo.core.journey.application;

import com.routiqo.core.journey.domain.Journey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JourneyStore {
    Journey start(UUID actorId, UUID journeyId, Journey.Kind kind, Instant now);
    Optional<Journey> find(UUID actorId, UUID journeyId);
    Journey complete(UUID actorId, UUID journeyId, Instant now);
    Page list(UUID actorId, Cursor before, int limit);

    record Cursor(Instant startedAt, UUID id) {
        public Cursor {
            java.util.Objects.requireNonNull(startedAt);
            java.util.Objects.requireNonNull(id);
        }
    }
    record Page(List<Journey> journeys, Cursor next) {
        public Page { journeys = List.copyOf(journeys); }
    }
}
