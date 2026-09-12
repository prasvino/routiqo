package com.routiqo.core.journal.application;

import com.routiqo.core.journal.domain.JournalAnnotation;
import com.routiqo.core.journal.domain.JournalMutation;
import com.routiqo.core.journey.application.JourneyService;
import com.routiqo.core.journey.domain.Journey;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public final class JournalService {
    private final JourneyService journeys;
    private final JournalStore store;
    private final Clock clock;

    public JournalService(JourneyService journeys, JournalStore store, Clock clock) {
        this.journeys = Objects.requireNonNull(journeys);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    public JournalView get(UUID actorId, UUID journeyId) {
        Journey journey = eligible(actorId, journeyId);
        return new JournalView(journey, store.find(actorId, journeyId).orElseGet(JournalAnnotation::empty));
    }

    public JournalView save(UUID actorId, UUID journeyId, JournalMutation mutation) {
        Journey journey = eligible(actorId, journeyId);
        JournalAnnotation annotation = store.save(actorId, journeyId, Objects.requireNonNull(mutation),
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        return new JournalView(journey, annotation);
    }

    private Journey eligible(UUID actorId, UUID journeyId) {
        Journey journey = journeys.get(Objects.requireNonNull(actorId), Objects.requireNonNull(journeyId));
        if (journey.kind() != Journey.Kind.TRIP || journey.status() != Journey.Status.COMPLETED)
            throw new JournalIneligible();
        return journey;
    }

    public record JournalView(Journey journey, JournalAnnotation annotation) {
        public JournalView { Objects.requireNonNull(journey); Objects.requireNonNull(annotation); }
        @Override public String toString() { return "JournalView[private]"; }
    }
}
