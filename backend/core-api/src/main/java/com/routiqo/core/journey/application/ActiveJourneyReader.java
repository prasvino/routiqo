package com.routiqo.core.journey.application;

import java.util.UUID;

/**
 * Read-only check that an authenticated account currently owns an active journey. Other modules use it
 * to scope reads to people on a journey without reaching journey storage or taking write locks.
 */
public interface ActiveJourneyReader {
    boolean hasActiveJourney(UUID ownerId);
}
