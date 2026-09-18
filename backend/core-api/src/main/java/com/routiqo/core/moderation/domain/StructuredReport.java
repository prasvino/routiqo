package com.routiqo.core.moderation.domain;

import java.time.Instant;
import java.util.UUID;

/** Opaque report metadata only. Reference authorization belongs to the application boundary. */
public record StructuredReport(UUID reporterId, UUID requestId, UUID evidenceRefId,
        Reason reason, Instant receivedAt) {
    private static final UUID NIL = new UUID(0, 0);

    public enum Reason { MISLEADING_INFORMATION, UNSAFE_CONTENT, HARASSMENT, SPAM_MANIPULATION }
    public enum SubmissionMatch { DIFFERENT_REQUEST, EXACT_REPLAY, CONFLICT }

    public StructuredReport {
        if (invalid(reporterId) || invalid(requestId) || invalid(evidenceRefId)
                || reason == null || receivedAt == null
                || receivedAt.equals(Instant.MIN) || receivedAt.equals(Instant.MAX)) {
            throw new IllegalArgumentException("Invalid structured report");
        }
    }

    public boolean sameRequest(UUID reporter, UUID request) {
        return reporterId.equals(reporter) && requestId.equals(request);
    }

    /** A retry compares the authenticated reporter scope and payload, never the receipt time. */
    public boolean matchesSubmission(UUID reporter, UUID request, UUID reference, Reason submittedReason) {
        return sameRequest(reporter, request) && evidenceRefId.equals(reference)
                && reason == submittedReason;
    }

    public SubmissionMatch classifySubmission(UUID reporter, UUID request, UUID reference,
            Reason submittedReason) {
        if (!sameRequest(reporter, request)) return SubmissionMatch.DIFFERENT_REQUEST;
        return matchesSubmission(reporter, request, reference, submittedReason)
                ? SubmissionMatch.EXACT_REPLAY : SubmissionMatch.CONFLICT;
    }

    private static boolean invalid(UUID id) { return id == null || NIL.equals(id); }
    @Override public String toString() { return "StructuredReport[private]"; }
}
