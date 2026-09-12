package com.routiqo.core.journal.application;

import com.routiqo.core.journal.domain.JournalAnnotation;
import com.routiqo.core.journal.domain.JournalMutation;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface JournalStore {
    Optional<JournalAnnotation> find(UUID actorId, UUID journeyId);
    JournalAnnotation save(UUID actorId, UUID journeyId, JournalMutation mutation, Instant now);
}
