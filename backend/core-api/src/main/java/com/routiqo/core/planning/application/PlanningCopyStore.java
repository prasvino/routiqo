package com.routiqo.core.planning.application;

import com.routiqo.core.planning.domain.AccountPlanningCopy;
import com.routiqo.core.planning.domain.PlanningMutation;
import java.time.Instant;
import java.util.UUID;

public interface PlanningCopyStore {
    /** Returns the owner's copy; an absent copy still reports the account's latest version. */
    AccountPlanningCopy find(UUID accountId);

    /** Compare-and-swap replacement with exact replay of the latest mutation. */
    AccountPlanningCopy save(UUID accountId, PlanningMutation mutation, Instant now);

    /**
     * Removes the copy's content when its version matches, leaving a content-free tombstone with the next
     * version. Succeeds without change when no copy is present.
     */
    AccountPlanningCopy delete(UUID accountId, long expectedVersion, Instant now);
}
