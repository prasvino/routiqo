package com.routiqo.core.moderation.application;

import java.util.UUID;

/**
 * Checks an operator's current grant inside the caller's transaction and holds it until commit, so a
 * concurrent revoke waits for the action (ADR 0075). Other modules use this instead of grant tables.
 */
public interface OperatorGrantAuthority {
    /** @throws OperatorNotPermitted unless the enabled operator holds a current grant of this permission. */
    void requireCurrent(UUID operator, String permission);
}
