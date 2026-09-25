package com.routiqo.core.planning.application;

public final class PlanningDocumentTooLarge extends RuntimeException {
    public PlanningDocumentTooLarge() { super("Account planning copy is too large"); }
}
