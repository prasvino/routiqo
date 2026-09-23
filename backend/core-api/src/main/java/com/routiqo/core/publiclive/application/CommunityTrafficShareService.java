package com.routiqo.core.publiclive.application;

import com.routiqo.core.identity.application.AccountWriteAuthority;
import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.moderation.application.ContributionRestrictionReader;
import com.routiqo.core.moderation.domain.ContributorAssessment;
import com.routiqo.core.privacy.application.PresenceConsentParticipant;
import com.routiqo.core.routeupdate.application.LiveRouteContextParticipant;
import com.routiqo.core.routeupdate.application.SignalStorageStore;
import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import com.routiqo.core.routeupdate.domain.SignalCommandGrant;
import com.routiqo.core.verification.application.VerifiedContributorReader;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Fresh, explicit V3 purpose admission. An accepted candidate is only considered at snapshot. */
public final class CommunityTrafficShareService {
    private static final UUID NIL = new UUID(0, 0);
    private final JourneyWriteAuthority journeys;
    private final AccountWriteAuthority accounts;
    private final PresenceConsentParticipant consents;
    private final LiveRouteContextParticipant contexts;
    private final ContributionRestrictionReader restrictions;
    private final VerifiedContributorReader verification;
    private final RouteAnchorCatalog catalog;
    private final SignalStorageStore receipts;
    private final CommunityTrafficCandidateStore store;
    private final Clock clock;

    public CommunityTrafficShareService(JourneyWriteAuthority journeys, AccountWriteAuthority accounts,
            PresenceConsentParticipant consents, LiveRouteContextParticipant contexts,
            ContributionRestrictionReader restrictions, VerifiedContributorReader verification,
            RouteAnchorCatalog catalog, SignalStorageStore receipts,
            CommunityTrafficCandidateStore store, Clock clock) {
        this.journeys = Objects.requireNonNull(journeys);
        this.accounts = Objects.requireNonNull(accounts);
        this.consents = Objects.requireNonNull(consents);
        this.contexts = Objects.requireNonNull(contexts);
        this.restrictions = Objects.requireNonNull(restrictions);
        this.verification = Objects.requireNonNull(verification);
        this.catalog = Objects.requireNonNull(catalog);
        this.receipts = Objects.requireNonNull(receipts);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    public CommunityTrafficCandidateStore.Candidate share(UUID actor, UUID journeyId,
            UUID commandId, UUID requestId) {
        if (invalid(actor) || invalid(journeyId) || invalid(commandId) || invalid(requestId))
            throw denied();
        return journeys.withOwnedJourney(actor, journeyId, journey -> {
            // The owner transaction precedes all retry checks. An exact lost-response retry
            // recovers even after completion, Ghost Mode, expiry, or a Stop.
            var prior = store.find(actor, commandId);
            if (prior != null) {
                if (!prior.journeyId().equals(journeyId) || !prior.requestId().equals(requestId))
                    throw new CommunityTrafficConflict();
                return prior;
            }
            if (journey.status() != Journey.Status.ACTIVE) throw denied();
            Instant now = clock.instant();
            var consent = consents.read(journey);
            var context = contexts.read(journey).orElseThrow(CommunityTrafficShareService::denied);
            var restriction = restrictions.read(actor);
            var grant = receipts.findGrant(actor, commandId)
                    .orElseThrow(CommunityTrafficShareService::denied);
            var receipt = receipts.findReceipt(actor, commandId)
                    .orElseThrow(CommunityTrafficShareService::denied);
            var signal = receipt.signal();
            if (verification.currentForFrozenShare(actor, now).isEmpty()
                    || !consent.sharing() || !consent.journeyActive()
                    || restriction.state() == ContributorAssessment.State.SUSPENDED
                    || grant.state() != SignalCommandGrant.State.CONSUMED
                    || !grant.commandId().equals(commandId)
                    || !grant.admission().actorId().equals(actor)
                    || !grant.admission().journeyId().equals(journeyId)
                    || !grant.admission().contextId().equals(receipt.contextId())
                    || grant.admission().routeRevision() != receipt.routeRevision()
                    || !grant.admission().anchorId().equals(signal.anchorId())
                    || grant.admission().consentGeneration() != signal.consentGeneration()
                    || !grant.admission().permittedCategories().contains(QuickSignalValue.Category.TRAFFIC)
                    || grant.restrictionRevision() != restriction.revision()
                    || !context.isCurrentAt(now)
                    || context.catalogVersion().isEmpty()
                    || !catalog.version().equals(context.catalogVersion().orElseThrow())
                    || receipt.state() != com.routiqo.core.routeupdate.domain.QuickSignalReceipt.State.ACTIVE
                    || !receipt.isEvidenceCurrent(now)
                    || !signal.actorId().equals(actor)
                    || !signal.signalId().equals(commandId)
                    || !signal.journeyId().equals(journeyId)
                    || !context.context().contextId().equals(receipt.contextId())
                    || context.context().revision() != receipt.routeRevision()
                    || consent.generation() != signal.consentGeneration()
                    || signal.value().category() != QuickSignalValue.Category.TRAFFIC
                    || !context.context().anchorIds().contains(signal.anchorId())
                    || catalog.anchors().stream().noneMatch(anchor ->
                            anchor.anchorId().equals(signal.anchorId())
                            && anchor.categories().contains(QuickSignalValue.Category.TRAFFIC)))
                throw denied();
            Instant window = Instant.ofEpochSecond(
                    Math.floorDiv(signal.receivedAt().getEpochSecond(), 300) * 300);
            if (!now.isBefore(window.plusSeconds(300))) throw denied();
            var candidate = new CommunityTrafficCandidateStore.Candidate(UUID.randomUUID(), actor,
                    journeyId, commandId, requestId, signal.anchorId(), signal.value().name(),
                    window, catalog.version(), consent.generation(), signal.receivedAt(),
                    now, window.plusSeconds(300 + 24 * 3600), CommunityTrafficCandidateStore.State.ACTIVE);
            return store.insert(candidate);
        });
    }

    public CommunityTrafficCandidateStore.Candidate stop(UUID actor, UUID journeyId, UUID commandId) {
        if (invalid(actor) || invalid(journeyId) || invalid(commandId)) throw denied();
        return accounts.withEnabledAccount(actor,
                () -> store.stop(actor, journeyId, commandId, clock.instant()));
    }

    public List<CommunityTrafficCandidateStore.Candidate> recover(UUID actor) {
        if (invalid(actor)) throw denied();
        return accounts.withEnabledAccount(actor,
                () -> store.recent(actor, clock.instant().minusSeconds(24 * 3600)));
    }

    private static boolean invalid(UUID id) { return id == null || NIL.equals(id); }
    private static SecurityException denied() { return new SecurityException("Community traffic share denied"); }
}
