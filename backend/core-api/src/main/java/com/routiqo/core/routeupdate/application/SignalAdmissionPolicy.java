package com.routiqo.core.routeupdate.application;

import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.privacy.domain.PresenceConsent;
import com.routiqo.core.routeupdate.domain.LiveRouteContext;
import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.SignalAdmission;
import java.time.Instant;
import java.util.UUID;

/** Pure policy over authoritative server snapshots; callers must revalidate at the eventual transaction boundary. */
public final class SignalAdmissionPolicy {
    public boolean permits(UUID authenticatedActorId, SignalAdmission admission,
            LiveRouteContext routeContext, Journey journey, PresenceConsent consent,
            QuickSignalValue value, Instant now) {
        if (authenticatedActorId == null || admission == null || routeContext == null
                || journey == null || consent == null
                || value == null || now == null || !admission.isCurrent(now))
            return false;
        if (journey.status() != Journey.Status.ACTIVE || journey.startedAt().isAfter(now)
                || admission.issuedAt().isBefore(journey.startedAt()))
            return false;
        if (!admission.actorId().equals(authenticatedActorId)
                || !admission.actorId().equals(journey.ownerId())
                || !admission.actorId().equals(routeContext.actorId())
                || !admission.actorId().equals(consent.actorId())
                || !admission.journeyId().equals(journey.id())
                || !admission.journeyId().equals(routeContext.journeyId())
                || !admission.journeyId().equals(consent.journeyId()))
            return false;
        return consent.sharing() && consent.journeyActive()
                && consent.generation() == admission.consentGeneration()
                && routeContext.contextId().equals(admission.contextId())
                && routeContext.revision() == admission.routeRevision()
                && routeContext.anchorIds().contains(admission.anchorId())
                && admission.permittedCategories().contains(value.category());
    }
}
