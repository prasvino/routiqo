package com.routiqo.core.routeupdate.application;

import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.privacy.domain.PresenceConsent;
import com.routiqo.core.routeupdate.domain.LiveRouteContext;
import com.routiqo.core.routeupdate.domain.QuickSignalReceipt;
import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.SignalAdmission;
import com.routiqo.core.routeupdate.domain.SignalCommandGrant;
import java.time.Instant;
import java.util.UUID;

/**
 * Pure internal command decision policy. A new candidate still requires atomic authority, quota,
 * grant-consumption and receipt-insertion checks at the future persistence boundary.
 */
public final class SignalCommandPolicy {
    private static final UUID NIL_ID = new UUID(0, 0);
    private final SignalAdmissionPolicy admissionPolicy = new SignalAdmissionPolicy();

    public enum Decision {
        NEW_ACCEPTANCE_CANDIDATE,
        RETAINED_REPLAY,
        CONFLICT,
        DENIED
    }

    /** Submitted command fields only; the command and actor identities are evaluated separately. */
    public record SubmissionFingerprint(
            UUID journeyId,
            UUID anchorId,
            QuickSignalValue value,
            long consentGeneration,
            UUID contextId,
            long routeRevision) {
        private boolean isValid() {
            return validId(journeyId) && validId(anchorId) && value != null
                    && consentGeneration >= 0 && validId(contextId) && routeRevision >= 0;
        }

        @Override
        public String toString() {
            return "SubmissionFingerprint[private]";
        }
    }

    public Decision decide(
            UUID authenticatedActorId,
            UUID commandId,
            SubmissionFingerprint submission,
            QuickSignalReceipt storedReceipt,
            SignalCommandGrant storedGrant,
            LiveRouteContext routeContext,
            Journey journey,
            PresenceConsent consent,
            Instant now) {
        if (!validId(authenticatedActorId) || !validId(commandId) || submission == null
                || !submission.isValid() || journey == null || now == null) {
            return Decision.DENIED;
        }

        if (storedReceipt != null) {
            if (!storedReceipt.signal().actorId().equals(authenticatedActorId)
                    || !storedReceipt.signal().signalId().equals(commandId)
                    || now.isBefore(storedReceipt.signal().receivedAt())) {
                return Decision.DENIED;
            }
            if (storedReceipt.isRetainedAt(now)) {
                return retainedDecision(
                        authenticatedActorId, commandId, submission, storedReceipt, journey);
            }
            return Decision.DENIED;
        }

        return newAcceptanceDecision(authenticatedActorId, commandId, submission, storedGrant,
                routeContext, journey, consent, now);
    }

    private Decision retainedDecision(
            UUID authenticatedActorId,
            UUID commandId,
            SubmissionFingerprint submission,
            QuickSignalReceipt receipt,
            Journey journey) {
        if (!journey.ownerId().equals(authenticatedActorId)
                || !journey.id().equals(receipt.signal().journeyId())) {
            return Decision.DENIED;
        }
        return receipt.matchesSubmission(
                authenticatedActorId,
                submission.journeyId(),
                submission.anchorId(),
                submission.value(),
                submission.consentGeneration(),
                submission.contextId(),
                submission.routeRevision())
                ? Decision.RETAINED_REPLAY
                : Decision.CONFLICT;
    }

    private Decision newAcceptanceDecision(
            UUID authenticatedActorId,
            UUID commandId,
            SubmissionFingerprint submission,
            SignalCommandGrant grant,
            LiveRouteContext routeContext,
            Journey journey,
            PresenceConsent consent,
            Instant now) {
        if (grant == null || !grant.commandId().equals(commandId)
                || !grant.admission().actorId().equals(authenticatedActorId)
                || !grant.isUnusedAt(now)
                || !matchesAdmission(submission, grant.admission())) {
            return Decision.DENIED;
        }
        return admissionPolicy.permits(authenticatedActorId, grant.admission(), routeContext,
                journey, consent, submission.value(), now)
                ? Decision.NEW_ACCEPTANCE_CANDIDATE
                : Decision.DENIED;
    }

    private static boolean matchesAdmission(
            SubmissionFingerprint submission, SignalAdmission admission) {
        return admission.journeyId().equals(submission.journeyId())
                && admission.anchorId().equals(submission.anchorId())
                && admission.consentGeneration() == submission.consentGeneration()
                && admission.contextId().equals(submission.contextId())
                && admission.routeRevision() == submission.routeRevision()
                && admission.permittedCategories().contains(submission.value().category());
    }

    private static boolean validId(UUID id) {
        return id != null && !NIL_ID.equals(id);
    }
}
