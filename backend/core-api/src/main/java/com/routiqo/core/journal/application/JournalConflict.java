package com.routiqo.core.journal.application;

public final class JournalConflict extends RuntimeException {
    public JournalConflict() { super("Journal annotation conflict"); }
}
