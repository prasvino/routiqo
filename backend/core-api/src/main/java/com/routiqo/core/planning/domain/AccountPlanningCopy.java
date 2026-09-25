package com.routiqo.core.planning.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * An account's planning copy. When no copy is present the document is empty and there is no timestamp;
 * the version still counts every change (including removals) so it never repeats for an account.
 */
public record AccountPlanningCopy(PlanningDocument document, long version, Instant updatedAt, boolean present) {
    public static final long MAX_VERSION = 9_007_199_254_740_991L;
    public static final long MAX_EXPECTED_VERSION = MAX_VERSION - 1;

    public AccountPlanningCopy {
        Objects.requireNonNull(document);
        if (version < 0 || version > MAX_VERSION) throw new IllegalArgumentException("Invalid planning version");
        if (present != (updatedAt != null)) throw new IllegalArgumentException("Invalid planning timestamp");
        if (present && version == 0) throw new IllegalArgumentException("A present copy has a version");
        if (!present && !document.isEmpty()) throw new IllegalArgumentException("An absent copy must be empty");
    }

    public static AccountPlanningCopy absent(long version) {
        return new AccountPlanningCopy(PlanningDocument.empty(), version, null, false);
    }

    public static AccountPlanningCopy absent() {
        return absent(0);
    }

    public static AccountPlanningCopy present(PlanningDocument document, long version, Instant updatedAt) {
        return new AccountPlanningCopy(document, version, Objects.requireNonNull(updatedAt), true);
    }

    @Override public String toString() { return "AccountPlanningCopy[private]"; }
}
