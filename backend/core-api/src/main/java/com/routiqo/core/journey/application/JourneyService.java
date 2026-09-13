package com.routiqo.core.journey.application;

import com.routiqo.core.journey.domain.Journey;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

/** Actor must come from verified identity when HTTP integration is introduced. */
public final class JourneyService {
    private final JourneyStore store;
    private final JourneyWriteAuthority writes;
    private final List<JourneyCompletionParticipant> completionParticipants;
    private final Clock clock;

    public JourneyService(JourneyStore store, JourneyWriteAuthority writes,
            List<JourneyCompletionParticipant> completionParticipants, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.writes = Objects.requireNonNull(writes);
        this.completionParticipants = completionParticipants.stream()
                .sorted(Comparator.comparingInt(JourneyCompletionParticipant::completionOrder))
                .toList();
        this.clock = Objects.requireNonNull(clock);
    }
    public Journey start(UUID actorId, UUID journeyId, Journey.Kind kind) {
        return store.start(Objects.requireNonNull(actorId), Objects.requireNonNull(journeyId),
                Objects.requireNonNull(kind), clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
    public Journey get(UUID actorId, UUID journeyId) {
        return store.find(Objects.requireNonNull(actorId), Objects.requireNonNull(journeyId))
                .orElseThrow(JourneyNotFound::new);
    }
    public Journey complete(UUID actorId, UUID journeyId) {
        Objects.requireNonNull(actorId); Objects.requireNonNull(journeyId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return writes.withOwnedJourney(actorId, journeyId, current -> {
            if (current.status() == Journey.Status.COMPLETED) {
                return current;
            }
            Journey completed = store.complete(actorId, journeyId, now);
            completionParticipants.forEach(participant -> participant.onCompleted(completed));
            return completed;
        });
    }
    public JourneyStore.Page list(UUID actorId, JourneyStore.Cursor before, int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Page size must be 1 to 50");
        return store.list(Objects.requireNonNull(actorId), before, limit);
    }
}
