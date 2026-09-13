package com.routiqo.core.privacy.application;

import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.privacy.domain.PresenceConsent;
import java.util.Objects;
import java.util.UUID;

/** Internal durable-consent service; actor IDs must come from independent authentication. */
public final class PresenceConsentService {
    private final JourneyWriteAuthority journeys;
    private final PresenceConsentParticipant consents;

    public PresenceConsentService(JourneyWriteAuthority journeys, PresenceConsentParticipant consents) {
        this.journeys = Objects.requireNonNull(journeys);
        this.consents = Objects.requireNonNull(consents);
    }

    public PresenceConsent read(UUID actorId, UUID journeyId) {
        return journeys.withOwnedJourney(actorId, journeyId, consents::read);
    }

    public PresenceConsent change(UUID actorId, UUID journeyId, long expectedGeneration, boolean sharing) {
        return journeys.withOwnedJourney(actorId, journeyId,
                journey -> consents.change(journey, expectedGeneration, sharing));
    }
}
