package com.routiqo.core.journal.domain;

import java.util.UUID;

public record JournalMutation(String title, String notes, long expectedVersion, UUID mutationId) {
    public JournalMutation {
        title = JournalAnnotation.validateTitle(title);
        notes = JournalAnnotation.validateNotes(notes);
        if (expectedVersion < 0 || expectedVersion > JournalAnnotation.MAX_EXPECTED_VERSION)
            throw new IllegalArgumentException("Invalid expected annotation version");
        if (mutationId == null) throw new IllegalArgumentException("Mutation identifier is required");
    }

    @Override public String toString() { return "JournalMutation[private]"; }
}
