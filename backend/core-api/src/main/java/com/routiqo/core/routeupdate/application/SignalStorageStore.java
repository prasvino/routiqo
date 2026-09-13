package com.routiqo.core.routeupdate.application;

import com.routiqo.core.routeupdate.domain.QuickSignalReceipt;
import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.SignalCommandGrant;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Trusted transaction participant; callers hold account, journey, consent and context locks as needed. */
public interface SignalStorageStore {
    enum BudgetAction { GRANT, ACCEPT }

    Optional<SignalCommandGrant> findGrant(UUID actorId, UUID commandId);

    Optional<QuickSignalReceipt> findReceipt(UUID actorId, UUID commandId);

    Optional<QuickSignalReceipt> findActiveSlot(
            UUID actorId, UUID anchorId, QuickSignalValue.Category category);

    void reserveBudget(UUID actorId, BudgetAction action, Instant now, int limit);

    void insertGrant(SignalCommandGrant grant);

    void consumeGrant(SignalCommandGrant grant);

    void supersede(QuickSignalReceipt receipt);

    void insertReceipt(QuickSignalReceipt receipt);

    void withdraw(QuickSignalReceipt receipt);
}
