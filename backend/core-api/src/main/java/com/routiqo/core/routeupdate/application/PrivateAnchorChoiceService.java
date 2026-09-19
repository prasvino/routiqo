package com.routiqo.core.routeupdate.application;

import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.moderation.application.ContributionRestrictionReader;
import com.routiqo.core.moderation.domain.ContributorAssessment;
import com.routiqo.core.privacy.application.PresenceConsentParticipant;
import com.routiqo.core.privacy.domain.PresenceConsent;
import com.routiqo.core.routeupdate.domain.PrivateAnchorChoice;
import com.routiqo.core.routeupdate.domain.PrivateAnchorChoiceSnapshot;
import com.routiqo.core.routeupdate.domain.RouteAnchor;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import com.routiqo.core.routeupdate.domain.StoredLiveRouteContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Internal owner read. It performs no provider, persistence-write or grant work. */
public final class PrivateAnchorChoiceService {
    private static final UUID NIL_ID = new UUID(0, 0);
    private final JourneyWriteAuthority journeys;
    private final PresenceConsentParticipant consents;
    private final LiveRouteContextParticipant contexts;
    private final ContributionRestrictionReader restrictions;
    private final RouteAnchorCatalog catalog;
    private final Clock clock;

    public PrivateAnchorChoiceService(JourneyWriteAuthority journeys,
            PresenceConsentParticipant consents, LiveRouteContextParticipant contexts,
            ContributionRestrictionReader restrictions, RouteAnchorCatalog catalog, Clock clock) {
        this.journeys = Objects.requireNonNull(journeys);
        this.consents = Objects.requireNonNull(consents);
        this.contexts = Objects.requireNonNull(contexts);
        this.restrictions = Objects.requireNonNull(restrictions);
        this.catalog = Objects.requireNonNull(catalog);
        this.clock = Objects.requireNonNull(clock);
    }

    public PrivateAnchorChoiceSnapshot read(UUID actorId, UUID journeyId) {
        if (invalid(actorId) || invalid(journeyId)) throw unavailable();
        return journeys.withOwnedJourney(actorId, journeyId,
                journey -> readOwned(actorId, journeyId, journey));
    }

    private PrivateAnchorChoiceSnapshot readOwned(UUID actorId, UUID journeyId, Journey journey) {
        if (journey == null || !actorId.equals(journey.ownerId())
                || !journeyId.equals(journey.id()) || journey.status() != Journey.Status.ACTIVE) {
            throw unavailable();
        }
        PresenceConsent consent = consents.read(journey);
        Optional<StoredLiveRouteContext> stored = contexts.read(journey);
        ContributorAssessment restriction = restrictions.read(actorId);
        Instant now = now();
        if (consent == null || !actorId.equals(consent.actorId())
                || !journeyId.equals(consent.journeyId()) || !consent.sharing()
                || !consent.journeyActive() || restriction == null
                || !actorId.equals(restriction.actorId())
                || restriction.state() == ContributorAssessment.State.SUSPENDED
                || stored == null || stored.isEmpty()) {
            throw unavailable();
        }
        StoredLiveRouteContext value = stored.orElseThrow();
        if (!value.isCurrentAt(now)
                || !value.catalogVersion().equals(Optional.of(catalog.version()))
                || !actorId.equals(value.context().actorId())
                || !journeyId.equals(value.context().journeyId())) {
            throw unavailable();
        }

        var anchors = new HashMap<UUID, RouteAnchor>();
        for (RouteAnchor anchor : catalog.anchors()) anchors.put(anchor.anchorId(), anchor);
        List<PrivateAnchorChoice> choices;
        try {
            choices = value.context().anchorIds().stream()
                    .sorted(Comparator.comparing(UUID::toString))
                    .map(id -> choice(id, anchors))
                    .toList();
            return new PrivateAnchorChoiceSnapshot(value.context().contextId(),
                    value.context().revision(), consent.generation(), value.issuedAt(),
                    value.expiresAt(), choices);
        } catch (RuntimeException invalid) {
            throw unavailable();
        }
    }

    private static PrivateAnchorChoice choice(UUID id, HashMap<UUID, RouteAnchor> anchors) {
        RouteAnchor anchor = anchors.get(id);
        if (anchor == null || anchor.displayLabel().isEmpty()) throw unavailable();
        return new PrivateAnchorChoice(id, anchor.displayLabel().orElseThrow(), anchor.categories());
    }

    private Instant now() {
        try {
            return clock.instant();
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
    }

    private static boolean invalid(UUID id) { return id == null || NIL_ID.equals(id); }
    private static PrivateAnchorChoicesUnavailable unavailable() {
        return new PrivateAnchorChoicesUnavailable();
    }

    @Override public String toString() { return "PrivateAnchorChoiceService[private]"; }
}
