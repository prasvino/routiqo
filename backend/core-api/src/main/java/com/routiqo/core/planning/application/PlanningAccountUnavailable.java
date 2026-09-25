package com.routiqo.core.planning.application;

/** The authenticated account disappeared (for example, deleted concurrently) before the write committed. */
public final class PlanningAccountUnavailable extends RuntimeException {
    public PlanningAccountUnavailable() { super("Account is unavailable"); }
}
