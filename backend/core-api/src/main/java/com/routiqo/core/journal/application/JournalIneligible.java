package com.routiqo.core.journal.application;

public final class JournalIneligible extends RuntimeException {
    public JournalIneligible() { super("Journey is not eligible for a journal"); }
}
