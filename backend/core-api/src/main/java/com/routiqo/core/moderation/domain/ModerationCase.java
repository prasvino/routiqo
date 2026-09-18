package com.routiqo.core.moderation.domain;

import java.util.UUID;

/** Private pure lifecycle. Durable operator authority, auditing and retention are external gates. */
public record ModerationCase(UUID caseId, StructuredReport report, long revision, State state,
        UUID resolutionRequestId, long preResolutionRevision) {
    private static final UUID NIL = new UUID(0, 0);

    public enum State { OPEN, DISMISSED, ACTIONED }

    public ModerationCase {
        if (caseId == null || NIL.equals(caseId) || report == null || revision < 0
                || state == null) throw invalid();
        if (state == State.OPEN) {
            if (resolutionRequestId != null || preResolutionRevision != -1) throw invalid();
        } else if (resolutionRequestId == null || NIL.equals(resolutionRequestId)
                || preResolutionRevision < 0 || preResolutionRevision == Long.MAX_VALUE
                || revision != preResolutionRevision + 1) {
            throw invalid();
        }
    }

    public static ModerationCase open(UUID caseId, StructuredReport report) {
        return new ModerationCase(caseId, report, 0, State.OPEN, null, -1);
    }

    public ModerationCase resolve(long expectedRevision, UUID resolutionRequest, State decision) {
        if (expectedRevision < 0 || expectedRevision > revision
                || resolutionRequest == null || NIL.equals(resolutionRequest)
                || decision == null || decision == State.OPEN) throw denied();
        if (state != State.OPEN) {
            if (expectedRevision == preResolutionRevision
                    && resolutionRequestId.equals(resolutionRequest) && state == decision) return this;
            throw denied();
        }
        if (expectedRevision != revision || revision == Long.MAX_VALUE) throw denied();
        return new ModerationCase(caseId, report, revision + 1, decision,
                resolutionRequest, expectedRevision);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid moderation case");
    }
    private static IllegalStateException denied() {
        return new IllegalStateException("Moderation case transition denied");
    }
    @Override public String toString() { return "ModerationCase[private]"; }
}
