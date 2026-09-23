package com.routiqo.core.publiclive.application;

import com.routiqo.core.identity.application.AccountWriteAuthority;
import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.moderation.application.ContributionRestrictionReader;
import com.routiqo.core.moderation.domain.ContributorAssessment;
import com.routiqo.core.privacy.application.PresenceConsentParticipant;
import com.routiqo.core.routeupdate.application.LiveRouteContextParticipant;
import com.routiqo.core.routeupdate.application.SignalStorageStore;
import com.routiqo.core.routeupdate.domain.QuickSignalReceipt;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import com.routiqo.core.routeupdate.domain.SignalCommandGrant;
import com.routiqo.core.verification.application.VerifiedContributorReader;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Explicit public-purpose intent. This service does not publish or expose a moment. */
public final class PublicSignalIntentService {
    private static final UUID NIL = new UUID(0, 0);
    private final JourneyWriteAuthority journeys;
    private final AccountWriteAuthority accounts;
    private final PresenceConsentParticipant consents;
    private final LiveRouteContextParticipant contexts;
    private final ContributionRestrictionReader restrictions;
    private final VerifiedContributorReader verification;
    private final RouteAnchorCatalog catalog;
    private final PublicSignalIntentStore store;
    private final SignalStorageStore receipts;
    private final Clock clock;

    public PublicSignalIntentService(JourneyWriteAuthority journeys, AccountWriteAuthority accounts,
            PresenceConsentParticipant consents, LiveRouteContextParticipant contexts,
            ContributionRestrictionReader restrictions, VerifiedContributorReader verification,
            RouteAnchorCatalog catalog, PublicSignalIntentStore store,
            SignalStorageStore receipts, Clock clock) {
        this.journeys = Objects.requireNonNull(journeys);
        this.accounts = Objects.requireNonNull(accounts);
        this.consents = Objects.requireNonNull(consents);
        this.contexts = Objects.requireNonNull(contexts);
        this.restrictions = Objects.requireNonNull(restrictions);
        this.verification = Objects.requireNonNull(verification);
        this.catalog = Objects.requireNonNull(catalog);
        this.store = Objects.requireNonNull(store);
        this.receipts = Objects.requireNonNull(receipts);
        this.clock = Objects.requireNonNull(clock);
    }

    public PublicSignalIntentStore.Intent share(UUID actor, UUID journeyId, UUID commandId,
            UUID requestId) {
        if (invalid(actor) || invalid(journeyId) || invalid(commandId) || invalid(requestId))
            throw denied();
        return journeys.withOwnedJourney(actor, journeyId, journey -> {
            if (journey.status() != Journey.Status.ACTIVE) throw denied();
            Instant now = clock.instant();
            var verified = verification.current(actor, now).orElseThrow(PublicSignalIntentService::denied);
            var consent = consents.read(journey);
            var context = contexts.read(journey).orElseThrow(PublicSignalIntentService::denied);
            var restriction = restrictions.read(actor);
            var grant = receipts.findGrant(actor, commandId)
                    .orElseThrow(PublicSignalIntentService::denied);
            QuickSignalReceipt receipt = receipts.findReceipt(actor, commandId)
                    .orElseThrow(PublicSignalIntentService::denied);
            var signal = receipt.signal();
            if (!consent.sharing() || !consent.journeyActive()
                    || restriction.state() == ContributorAssessment.State.SUSPENDED
                    || grant.state() != SignalCommandGrant.State.CONSUMED
                    || grant.restrictionRevision() != restriction.revision()
                    || !context.isCurrentAt(now)
                    || context.catalogVersion().isEmpty()
                    || !context.catalogVersion().orElseThrow().equals(catalog.version())
                    || receipt.state() != QuickSignalReceipt.State.ACTIVE
                    || !receipt.isEvidenceCurrent(now)
                    || !signal.journeyId().equals(journeyId)
                    || !context.context().contextId().equals(receipt.contextId())
                    || context.context().revision() != receipt.routeRevision()
                    || consent.generation() != signal.consentGeneration()
                    || !context.context().anchorIds().contains(signal.anchorId())
                    || catalog.anchors().stream().noneMatch(anchor ->
                            anchor.anchorId().equals(signal.anchorId())
                            && anchor.categories().contains(signal.value().category())))
                throw denied();
            Instant window = Instant.ofEpochSecond(Math.floorDiv(signal.receivedAt().getEpochSecond(), 300) * 300);
            if (!now.isBefore(window.plusSeconds(300))) throw denied();
            var intent = new PublicSignalIntentStore.Intent(actor, commandId, journeyId,
                    verified.personRef(), verified.revision(), restriction.revision(), signal.anchorId(),
                    signal.value().category().name(), signal.value().name(), window,
                    signal.receivedAt(), signal.expiresAt(), now, requestId,
                    PublicSignalIntentStore.State.ACTIVE);
            return store.share(intent);
        });
    }

    /** Stopping is allowed after consent, verification or journey authority is gone. */
    public PublicSignalIntentStore.State stop(UUID actor, UUID journeyId, UUID commandId) {
        if (invalid(actor) || invalid(journeyId) || invalid(commandId)) throw denied();
        return accounts.withEnabledAccount(actor,
                () -> store.stop(actor, journeyId, commandId, clock.instant()));
    }

    /** Owner-only retained handles for Stop recovery across reloads and devices. */
    public PublicSignalIntentStore.HandlePage list(UUID actor,
            PublicSignalIntentStore.Cursor cursor) {
        if (invalid(actor)) throw denied();
        if (cursor != null && (invalid(cursor.commandId()) || cursor.sharedAt() == null))
            throw denied();
        return accounts.withEnabledAccount(actor,
                () -> store.list(actor, clock.instant().minusSeconds(24 * 60 * 60), cursor));
    }

    private static boolean invalid(UUID id) { return id == null || NIL.equals(id); }
    private static SecurityException denied() { return new SecurityException("Public share denied"); }
}
