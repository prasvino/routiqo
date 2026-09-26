package com.routiqo.core.moderation.application;

import java.util.Set;
import java.util.UUID;

/**
 * The accounts a viewer currently blocks (at most 100 edges), for filtering what that viewer reads.
 * A read-only snapshot: writes and delivery keep their own pair authority.
 */
public interface BlockedAccountsReader {
    Set<UUID> blockedBy(UUID viewerId);
}
