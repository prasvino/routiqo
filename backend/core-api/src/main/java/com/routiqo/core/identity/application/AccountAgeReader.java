package com.routiqo.core.identity.application;

import java.time.Instant;
import java.util.UUID;

/** When an enabled account was created, for "new account" tiers; empty when it does not exist. */
public interface AccountAgeReader {
    java.util.Optional<Instant> createdAt(UUID accountId);
}
