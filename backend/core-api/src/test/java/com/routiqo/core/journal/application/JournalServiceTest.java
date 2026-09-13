package com.routiqo.core.journal.application;

import com.routiqo.core.journal.domain.JournalAnnotation;
import com.routiqo.core.journal.domain.JournalMutation;
import com.routiqo.core.journey.application.JourneyService;
import com.routiqo.core.journey.application.JourneyStore;
import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.journey.domain.Journey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JournalServiceTest {
    static final Instant NOW = Instant.parse("2026-09-12T08:30:00.123456789Z");

    @Test void onlyAnOwnedCompletedTripReachesTheJournalStore() {
        UUID actor = UUID.randomUUID(); UUID id = UUID.randomUUID();
        var journalStore = new RecordingJournalStore();
        for (Journey journey : List.of(
                new Journey(id, actor, Journey.Kind.TRIP, Journey.Status.ACTIVE, NOW.minusSeconds(60), null),
                new Journey(id, actor, Journey.Kind.COMMUTE, Journey.Status.COMPLETED, NOW.minusSeconds(60), NOW))) {
            var service = service(journey, journalStore);
            assertThatThrownBy(() -> service.get(actor, id)).isInstanceOf(JournalIneligible.class);
            assertThatThrownBy(() -> service.save(actor, id, mutation())).isInstanceOf(JournalIneligible.class);
        }
        assertThat(journalStore.calls).isZero();

        Journey completedTrip = new Journey(id, actor, Journey.Kind.TRIP, Journey.Status.COMPLETED,
                NOW.minusSeconds(60), NOW);
        assertThat(service(completedTrip, journalStore).get(actor, id).annotation()).isEqualTo(JournalAnnotation.empty());
        assertThat(journalStore.calls).isEqualTo(1);
    }

    @Test void saveUsesMicrosecondTimeAndPreservesValidatedMutation() {
        UUID actor = UUID.randomUUID(); UUID id = UUID.randomUUID();
        Journey completedTrip = new Journey(id, actor, Journey.Kind.TRIP, Journey.Status.COMPLETED,
                NOW.minusSeconds(60), NOW);
        var store = new RecordingJournalStore();
        var mutation = new JournalMutation(" title ", " notes\n", 0, UUID.randomUUID());
        var annotation = service(completedTrip, store).save(actor, id, mutation).annotation();
        assertThat(annotation.title()).isEqualTo(" title ");
        assertThat(annotation.notes()).isEqualTo(" notes\n");
        assertThat(annotation.updatedAt()).isEqualTo(Instant.parse("2026-09-12T08:30:00.123456Z"));
    }

    private static JournalService service(Journey journey, JournalStore journals) {
        JourneyStore journeys = new JourneyStore() {
            @Override public Optional<Journey> find(UUID actorId, UUID journeyId) {
                return journey.ownerId().equals(actorId) && journey.id().equals(journeyId)
                        ? Optional.of(journey) : Optional.empty();
            }
            @Override public Journey start(UUID actorId, UUID journeyId, Journey.Kind kind, Instant now) { throw new UnsupportedOperationException(); }
            @Override public Journey complete(UUID actorId, UUID journeyId, Instant now) { throw new UnsupportedOperationException(); }
            @Override public Page list(UUID actorId, Cursor before, int limit) { throw new UnsupportedOperationException(); }
        };
        JourneyWriteAuthority writes = new JourneyWriteAuthority() {
            @Override public <T> T withOwnedJourney(UUID actorId, UUID journeyId, Work<T> work) {
                throw new UnsupportedOperationException();
            }
        };
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new JournalService(new JourneyService(journeys, writes, List.of(), clock), journals, clock);
    }

    private static JournalMutation mutation() { return new JournalMutation("", "", 0, UUID.randomUUID()); }

    private static final class RecordingJournalStore implements JournalStore {
        int calls;
        @Override public Optional<JournalAnnotation> find(UUID actorId, UUID journeyId) { calls++; return Optional.empty(); }
        @Override public JournalAnnotation save(UUID actorId, UUID journeyId, JournalMutation mutation, Instant now) {
            calls++; return new JournalAnnotation(mutation.title(), mutation.notes(), 1, now);
        }
    }
}
