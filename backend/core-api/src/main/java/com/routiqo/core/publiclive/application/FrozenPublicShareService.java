package com.routiqo.core.publiclive.application;

import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.moderation.application.ContributionRestrictionReader;
import com.routiqo.core.privacy.application.PresenceConsentParticipant;
import com.routiqo.core.routeupdate.application.LiveRouteContextParticipant;
import com.routiqo.core.routeupdate.application.SignalStorageStore;
import com.routiqo.core.routeupdate.domain.QuickSignalReceipt;
import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import com.routiqo.core.routeupdate.domain.SignalCommandGrant;
import com.routiqo.core.verification.application.VerifiedContributorReader;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Disconnected V2 frozen-input foundation. This is not a public action or release protocol:
 * stable person assignment and a commit/window seal still require independent approval.
 */
public final class FrozenPublicShareService {
    private static final UUID NIL = new UUID(0, 0);
    private final UUID pilotId;
    private final JourneyWriteAuthority journeys;
    private final PresenceConsentParticipant consents;
    private final LiveRouteContextParticipant contexts;
    private final ContributionRestrictionReader restrictions;
    private final VerifiedContributorReader verification;
    private final RouteAnchorCatalog catalog;
    private final SignalStorageStore receipts;
    private final FrozenPublicShareStore store;
    private final Clock clock;

    public FrozenPublicShareService(UUID pilotId, JourneyWriteAuthority journeys,
            PresenceConsentParticipant consents, LiveRouteContextParticipant contexts,
            ContributionRestrictionReader restrictions, VerifiedContributorReader verification,
            RouteAnchorCatalog catalog, SignalStorageStore receipts,
            FrozenPublicShareStore store, Clock clock) {
        if (invalid(pilotId)) throw new IllegalArgumentException("Pilot required");
        this.pilotId = pilotId;
        this.journeys = Objects.requireNonNull(journeys);
        this.consents = Objects.requireNonNull(consents);
        this.contexts = Objects.requireNonNull(contexts);
        this.restrictions = Objects.requireNonNull(restrictions);
        this.verification = Objects.requireNonNull(verification);
        this.catalog = Objects.requireNonNull(catalog);
        this.receipts = Objects.requireNonNull(receipts);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    public FrozenPublicShareStore.Acknowledgement share(UUID actor, UUID journeyId,
            UUID commandId, UUID requestId) {
        if (invalid(actor) || invalid(journeyId) || invalid(commandId) || invalid(requestId))
            throw denied();
        return journeys.withOwnedJourney(actor, journeyId, journey -> {
            // A lost-response retry recovers the committed outcome after Stop, Ghost,
            // completion, verification expiry or restriction. The owner lock comes first.
            var prior = store.findOwner(pilotId, actor, journeyId, commandId, requestId);
            if (prior != null) return prior;
            if (journey.status() != Journey.Status.ACTIVE) throw denied();
            Instant now = clock.instant();
            var manifest = store.manifest(pilotId);
            if (!manifest.catalogVersion().equals(catalog.version())) throw denied();
            var verified = verification.currentForFrozenShare(actor, now)
                    .orElseThrow(FrozenPublicShareService::denied);
            var consent = consents.read(journey);
            var context = contexts.read(journey).orElseThrow(FrozenPublicShareService::denied);
            var restriction = restrictions.read(actor);
            var grant = receipts.findGrant(actor, commandId)
                    .orElseThrow(FrozenPublicShareService::denied);
            QuickSignalReceipt receipt = receipts.findReceipt(actor, commandId)
                    .orElseThrow(FrozenPublicShareService::denied);
            var signal = receipt.signal();
            if (!consent.sharing() || !consent.journeyActive()
                    || !restriction.eligibleAt(now)
                    || grant.state() != SignalCommandGrant.State.CONSUMED
                    || grant.restrictionRevision() != restriction.revision()
                    || !context.isCurrentAt(now)
                    || context.catalogVersion().isEmpty()
                    || !manifest.catalogVersion().equals(context.catalogVersion().orElseThrow())
                    || receipt.state() != QuickSignalReceipt.State.ACTIVE
                    || !receipt.isEvidenceCurrent(now)
                    || !signal.actorId().equals(actor)
                    || !signal.journeyId().equals(journeyId)
                    || !context.context().contextId().equals(receipt.contextId())
                    || context.context().revision() != receipt.routeRevision()
                    || consent.generation() != signal.consentGeneration()
                    || signal.value().category() != QuickSignalValue.Category.TRAFFIC
                    || !manifest.anchorIds().contains(signal.anchorId())
                    || !context.context().anchorIds().contains(signal.anchorId())
                    || catalog.anchors().stream().noneMatch(anchor ->
                            anchor.anchorId().equals(signal.anchorId())
                            && anchor.categories().contains(QuickSignalValue.Category.TRAFFIC)))
                throw denied();
            Instant window = Instant.ofEpochSecond(
                    Math.floorDiv(signal.receivedAt().getEpochSecond(), 300) * 300);
            if (window.isBefore(manifest.startsAt())
                    || window.plusSeconds(300).isAfter(manifest.endsAt())) throw denied();
            // This admission check does not establish commit-order sealing at the close.
            // No publisher may consume this disconnected foundation until ADR 0054 P1 is solved.
            if (!now.isBefore(window.plusSeconds(300))) throw denied();
            String key = "v2:" + signal.anchorId() + ":" + signal.value().name()
                    + ":" + window.getEpochSecond();
            return store.freeze(pilotId, verified.personRef(), actor,
                    journeyId, commandId, requestId, key);
        });
    }

    private static boolean invalid(UUID id) { return id == null || NIL.equals(id); }
    private static SecurityException denied() { return new SecurityException("Frozen public Share denied"); }
}
