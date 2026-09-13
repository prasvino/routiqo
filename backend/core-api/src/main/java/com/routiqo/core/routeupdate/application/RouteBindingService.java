package com.routiqo.core.routeupdate.application;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.privacy.application.PresenceConsentParticipant;
import com.routiqo.core.privacy.domain.PresenceConsent;
import com.routiqo.core.routeupdate.domain.ResolvedRouteAnchors;
import com.routiqo.core.routeupdate.domain.RouteBindingAttempt;
import com.routiqo.core.routeupdate.domain.RouteBindingOutcome;
import com.routiqo.core.routeupdate.domain.StoredLiveRouteContext;
import com.routiqo.core.routing.domain.RouteRequest;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Internal two-transaction coordinator. Actor identity and route request are trusted server inputs. */
public final class RouteBindingService {
    private static final UUID NIL_ID = new UUID(0, 0);
    private static final Duration CONTEXT_LIFETIME = Duration.ofMinutes(15);
    private static final String RATE_CATEGORY = "route-binding-account";
    private static final int RATE_LIMIT = 10;
    private final JourneyWriteAuthority journeys;
    private final PresenceConsentParticipant consents;
    private final LiveRouteContextParticipant contexts;
    private final RouteBindingAttemptParticipant attempts;
    private final RouteAnchorResolver resolver;
    private final AuthRateGate rates;
    private final Supplier<UUID> newId;

    public RouteBindingService(JourneyWriteAuthority journeys, PresenceConsentParticipant consents,
            LiveRouteContextParticipant contexts, RouteBindingAttemptParticipant attempts,
            RouteAnchorResolver resolver, AuthRateGate rates) {
        this(journeys, consents, contexts, attempts, resolver, rates, UUID::randomUUID);
    }

    RouteBindingService(JourneyWriteAuthority journeys, PresenceConsentParticipant consents,
            LiveRouteContextParticipant contexts, RouteBindingAttemptParticipant attempts,
            RouteAnchorResolver resolver, AuthRateGate rates, Supplier<UUID> newId) {
        this.journeys = Objects.requireNonNull(journeys);
        this.consents = Objects.requireNonNull(consents);
        this.contexts = Objects.requireNonNull(contexts);
        this.attempts = Objects.requireNonNull(attempts);
        this.resolver = Objects.requireNonNull(resolver);
        this.rates = Objects.requireNonNull(rates);
        this.newId = Objects.requireNonNull(newId);
    }

    public RouteBindingOutcome bind(UUID actorId, UUID journeyId, RouteRequest request,
            int selectedAlternative, Optional<UUID> expectedCurrentContextId) {
        validate(request, selectedAlternative, expectedCurrentContextId);
        UUID catalogVersion = resolver.catalogVersion();
        RouteBindingAttempt attempt = journeys.withOwnedJourney(actorId, journeyId, journey -> {
            requireActive(journey);
            PresenceConsent consent = activeConsent(journey);
            Optional<StoredLiveRouteContext> current = contexts.read(journey);
            requireExpectation(current, expectedCurrentContextId);
            reserveRate(actorId);
            return attempts.reserve(journey, consent.generation(), catalogVersion,
                    expectedCurrentContextId, requiredNewId());
        });

        ResolvedRouteAnchors result;
        try {
            result = resolver.resolve(request, selectedAlternative);
        } catch (RuntimeException providerFailure) {
            throw new RouteBindingUnavailable();
        }

        return journeys.withOwnedJourney(actorId, journeyId, journey -> {
            requireActive(journey);
            PresenceConsent consent = activeConsent(journey);
            if (consent.generation() != attempt.consentGeneration()) throw conflict();
            Optional<StoredLiveRouteContext> current = contexts.read(journey);
            requireExpectation(current, attempt.expectedContextId());
            attempts.consume(attempt, current, result.catalogVersion());
            if (!result.hasRoute()) {
                return RouteBindingOutcome.empty(RouteBindingOutcome.Status.NO_ROUTE);
            }
            if (result.anchors().isEmpty()) {
                return RouteBindingOutcome.empty(RouteBindingOutcome.Status.NO_ELIGIBLE_ANCHORS);
            }
            StoredLiveRouteContext context = contexts.replace(journey, result.anchors().keySet(),
                    CONTEXT_LIFETIME, attempt.expectedContextId(), requiredNewId());
            return RouteBindingOutcome.bound(context);
        });
    }

    private void reserveRate(UUID actorId) {
        boolean allowed;
        try {
            allowed = rates.allow(actorId.toString(), RATE_CATEGORY, RATE_LIMIT);
        } catch (RuntimeException unavailable) {
            throw new RouteBindingUnavailable();
        }
        if (!allowed) throw new RouteBindingRateLimited();
    }

    private static PresenceConsent activeConsent(Journey journey,
            PresenceConsentParticipant consents) {
        PresenceConsent consent = consents.read(journey);
        if (!consent.sharing() || !consent.journeyActive()) throw conflict();
        return consent;
    }

    private PresenceConsent activeConsent(Journey journey) {
        return activeConsent(journey, consents);
    }

    private static void requireActive(Journey journey) {
        if (journey == null || journey.status() != Journey.Status.ACTIVE) throw conflict();
    }

    private static void requireExpectation(Optional<StoredLiveRouteContext> current,
            Optional<UUID> expected) {
        Optional<UUID> actual = current.map(value -> value.context().contextId());
        if (!actual.equals(expected)) throw conflict();
    }

    private UUID requiredNewId() {
        UUID value;
        try {
            value = newId.get();
        } catch (RuntimeException unavailable) {
            throw conflict();
        }
        if (value == null || NIL_ID.equals(value)) throw conflict();
        return value;
    }

    private static void validate(RouteRequest request, int alternative,
            Optional<UUID> expectedContextId) {
        if (request == null || alternative < 0 || alternative > 2 || expectedContextId == null
                || expectedContextId.filter(id -> id == null || NIL_ID.equals(id)).isPresent()) {
            throw conflict();
        }
    }

    private static RouteBindingConflict conflict() { return new RouteBindingConflict(); }

    @Override public String toString() { return "RouteBindingService[private]"; }
}
