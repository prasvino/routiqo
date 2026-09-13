package com.routiqo.core.routeupdate.application;

import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.privacy.application.PresenceConsentParticipant;
import com.routiqo.core.privacy.domain.PresenceConsent;
import com.routiqo.core.routeupdate.domain.QuickSignal;
import com.routiqo.core.routeupdate.domain.QuickSignalReceipt;
import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.SignalAdmission;
import com.routiqo.core.routeupdate.domain.SignalCommandGrant;
import com.routiqo.core.routeupdate.domain.StoredLiveRouteContext;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Internal transaction service. Identifiers and anchors are trusted server inputs, never public authority. */
public final class SignalStorageService {
    private static final Duration GRANT_LIFETIME = Duration.ofSeconds(90);
    private static final Duration MAX_EVIDENCE_LIFETIME = Duration.ofMinutes(15);
    private static final Duration MAX_RETENTION = Duration.ofHours(24);
    private final JourneyWriteAuthority journeys;
    private final PresenceConsentParticipant consents;
    private final LiveRouteContextParticipant contexts;
    private final SignalStorageStore store;
    private final Clock clock;
    private final SignalCommandPolicy policy = new SignalCommandPolicy();

    public SignalStorageService(JourneyWriteAuthority journeys,
            PresenceConsentParticipant consents,
            LiveRouteContextParticipant contexts,
            SignalStorageStore store,
            Clock clock) {
        this.journeys = Objects.requireNonNull(journeys);
        this.consents = Objects.requireNonNull(consents);
        this.contexts = Objects.requireNonNull(contexts);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    public SignalCommandGrant issue(UUID actorId, UUID journeyId, UUID anchorId,
            Set<QuickSignalValue.Category> permittedCategories) {
        return journeys.withOwnedJourney(actorId, journeyId, journey -> {
            if (journey.status() != Journey.Status.ACTIVE || permittedCategories == null
                    || permittedCategories.isEmpty() || anchorId == null) {
                throw denied();
            }
            PresenceConsent consent = consents.read(journey);
            StoredLiveRouteContext storedContext = contexts.read(journey).orElseThrow(
                    SignalStorageService::denied);
            Instant now = now();
            if (!consent.sharing() || !consent.journeyActive()
                    || !storedContext.isCurrentAt(now)
                    || !storedContext.context().anchorIds().contains(anchorId)) {
                throw denied();
            }
            Instant expiresAt = min(normalizedAfter(now, GRANT_LIFETIME), storedContext.expiresAt());
            if (!expiresAt.isAfter(now)) {
                throw denied();
            }
            SignalAdmission admission;
            try {
                admission = new SignalAdmission(actorId, journeyId,
                        storedContext.context().contextId(), anchorId,
                        storedContext.context().revision(), consent.generation(),
                        permittedCategories, now, expiresAt);
            } catch (IllegalArgumentException invalid) {
                throw denied();
            }
            SignalCommandGrant grant = new SignalCommandGrant(
                    UUID.randomUUID(), admission, SignalCommandGrant.State.UNUSED);
            store.reserveBudget(actorId, SignalStorageStore.BudgetAction.GRANT, now, 10);
            store.insertGrant(grant);
            return grant;
        });
    }

    public QuickSignalReceipt accept(UUID actorId, UUID commandId,
            SignalCommandPolicy.SubmissionFingerprint submission,
            Duration evidenceLifetime, Duration retention) {
        return journeys.withOwnedJourney(actorId, requiredJourney(submission), journey -> {
            Optional<QuickSignalReceipt> existing = store.findReceipt(actorId, commandId);
            if (existing.isPresent()) {
                Instant now = now();
                SignalCommandPolicy.Decision decision = policy.decide(actorId, commandId,
                        submission, existing.get(), null, null, journey, null, now);
                if (decision == SignalCommandPolicy.Decision.RETAINED_REPLAY) {
                    return existing.get();
                }
                if (decision == SignalCommandPolicy.Decision.CONFLICT) {
                    throw conflict();
                }
                throw denied();
            }
            if (journey.status() != Journey.Status.ACTIVE) {
                throw denied();
            }
            PresenceConsent consent = consents.read(journey);
            StoredLiveRouteContext storedContext = contexts.read(journey).orElseThrow(
                    SignalStorageService::denied);
            SignalCommandGrant grant = store.findGrant(actorId, commandId).orElseThrow(
                    SignalStorageService::denied);
            QuickSignalValue.Category category = requiredCategory(submission);
            Optional<QuickSignalReceipt> prior = store.findActiveSlot(
                    actorId, submission.anchorId(), category);
            Instant now = now();
            if (!storedContext.isCurrentAt(now)) {
                throw denied();
            }
            SignalCommandPolicy.Decision decision = policy.decide(actorId, commandId,
                    submission, null, grant, storedContext.context(), journey, consent, now);
            if (decision != SignalCommandPolicy.Decision.NEW_ACCEPTANCE_CANDIDATE) {
                throw denied();
            }

            if (evidenceLifetime == null || retention == null
                    || retention.compareTo(evidenceLifetime) < 0) {
                throw conflict();
            }
            Instant evidenceExpires = normalizedDuration(now, evidenceLifetime,
                    MAX_EVIDENCE_LIFETIME);
            Instant retainUntil = normalizedDuration(now, retention, MAX_RETENTION);
            if (retainUntil.isBefore(evidenceExpires)) {
                throw conflict();
            }
            QuickSignal signal = new QuickSignal(commandId, actorId, submission.journeyId(),
                    submission.anchorId(), submission.value(), submission.consentGeneration(),
                    now, evidenceExpires);
            QuickSignalReceipt receipt = new QuickSignalReceipt(signal, submission.contextId(),
                    submission.routeRevision(), retainUntil, QuickSignalReceipt.State.ACTIVE);

            store.reserveBudget(actorId, SignalStorageStore.BudgetAction.ACCEPT, now, 5);
            prior.ifPresent(value -> store.supersede(value.supersede()));
            store.consumeGrant(grant.consume());
            store.insertReceipt(receipt);
            return receipt;
        });
    }

    public QuickSignalReceipt withdraw(UUID actorId, UUID journeyId, UUID commandId) {
        return journeys.withOwnedJourney(actorId, journeyId, journey -> {
            QuickSignalReceipt receipt = store.findReceipt(actorId, commandId).orElseThrow(
                    SignalStorageService::denied);
            Instant now = now();
            if (!receipt.signal().journeyId().equals(journey.id())
                    || now.isBefore(receipt.signal().receivedAt())
                    || !receipt.isRetainedAt(now)) {
                throw denied();
            }
            QuickSignalReceipt withdrawn = receipt.withdraw();
            if (withdrawn != receipt) {
                store.withdraw(withdrawn);
            }
            return withdrawn;
        });
    }

    private static UUID requiredJourney(SignalCommandPolicy.SubmissionFingerprint submission) {
        if (submission == null || submission.journeyId() == null) {
            throw denied();
        }
        return submission.journeyId();
    }

    private static QuickSignalValue.Category requiredCategory(
            SignalCommandPolicy.SubmissionFingerprint submission) {
        if (submission == null || submission.value() == null || submission.anchorId() == null) {
            throw denied();
        }
        return submission.value().category();
    }

    private Instant normalizedDuration(Instant now, Duration requested, Duration maximum) {
        if (requested == null || requested.isZero() || requested.isNegative()
                || requested.compareTo(maximum) > 0) {
            throw conflict();
        }
        Instant normalized = normalizedAfter(now, requested);
        if (!normalized.isAfter(now)) {
            throw conflict();
        }
        return normalized;
    }

    private static Instant normalizedAfter(Instant now, Duration duration) {
        try {
            return now.plus(duration).truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeException invalid) {
            throw conflict();
        }
    }

    private Instant now() {
        try {
            return clock.instant().truncatedTo(ChronoUnit.MICROS);
        } catch (RuntimeException unavailable) {
            throw denied();
        }
    }

    private static Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static SignalStorageConflict conflict() {
        return new SignalStorageConflict();
    }

    private static SignalStorageDenied denied() {
        return new SignalStorageDenied();
    }
}
