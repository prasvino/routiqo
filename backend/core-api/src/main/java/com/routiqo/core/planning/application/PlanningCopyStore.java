package com.routiqo.core.planning.application;

import com.routiqo.core.planning.domain.AccountPlanningCopy;
import com.routiqo.core.planning.domain.PlanningMutation;
import java.time.Instant;
import java.util.UUID;

public interface PlanningCopyStore {
    /** Returns the owner's copy, or {@link AccountPlanningCopy#absent()} when none is stored. */
    AccountPlanningCopy find(UUID accountId);

    /** Compare-and-swap replacement with exact replay of the latest mutation. */
    AccountPlanningCopy save(UUID accountId, PlanningMutation mutation, Instant now);

    /** Deletes the copy when its version matches; succeeds when no copy exists. */
    void delete(UUID accountId, long expectedVersion);
}
