package com.routiqo.core.planning.domain;

import java.time.Instant;
import java.util.Objects;

/** An account's planning copy. Version 0 is the derived "no copy" state with no timestamp and no content. */
public record AccountPlanningCopy(PlanningDocument document, long version, Instant updatedAt) {
    public static final long MAX_VERSION = 9_007_199_254_740_991L;
    public static final long MAX_EXPECTED_VERSION = MAX_VERSION - 1;

    public AccountPlanningCopy {
        Objects.requireNonNull(document);
        if (version < 0 || version > MAX_VERSION) throw new IllegalArgumentException("Invalid planning version");
        if ((version == 0) != (updatedAt == null)) throw new IllegalArgumentException("Invalid planning timestamp");
        if (version == 0 && !document.isEmpty()) throw new IllegalArgumentException("An absent copy must be empty");
    }

    public static AccountPlanningCopy absent() {
        return new AccountPlanningCopy(PlanningDocument.empty(), 0, null);
    }

    @Override public String toString() { return "AccountPlanningCopy[private]"; }
}
