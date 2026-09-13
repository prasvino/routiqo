package com.routiqo.core.privacy.application;

import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.privacy.domain.PresenceConsent;

/**
 * Privacy-owned persistence participant. Every call requires the caller's existing locked
 * account-and-owned-journey transaction on the same datasource and thread.
 */
public interface PresenceConsentParticipant {
    PresenceConsent read(Journey journey);

    PresenceConsent change(Journey journey, long expectedGeneration, boolean sharing);
}
