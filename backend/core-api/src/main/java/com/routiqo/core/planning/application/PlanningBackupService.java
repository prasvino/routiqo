package com.routiqo.core.planning.application;

import com.routiqo.core.planning.domain.AccountPlanningCopy;
import com.routiqo.core.planning.domain.PlanningMutation;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Owner-only account copy of planning state. Every operation is scoped to the authenticated account. */
public final class PlanningBackupService {
    private final PlanningCopyStore store;
    private final Clock clock;

    public PlanningBackupService(PlanningCopyStore store, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    public AccountPlanningCopy get(UUID accountId) {
        return store.find(Objects.requireNonNull(accountId));
    }

    public AccountPlanningCopy save(UUID accountId, PlanningMutation mutation) {
        return store.save(Objects.requireNonNull(accountId), Objects.requireNonNull(mutation),
                clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    public void delete(UUID accountId, long expectedVersion) {
        if (expectedVersion < 1 || expectedVersion > AccountPlanningCopy.MAX_VERSION)
            throw new IllegalArgumentException("Invalid expected planning version");
        store.delete(Objects.requireNonNull(accountId), expectedVersion);
    }
}
