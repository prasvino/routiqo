package com.routiqo.core.verification.application;

import com.routiqo.core.identity.application.EnabledAccountPairAuthority;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Internal command boundary. Caller must independently authenticate the reviewer. */
public final class VerifiedContributorService {
    private static final UUID NIL = new UUID(0, 0);
    private final EnabledAccountPairAuthority accounts;
    private final VerificationParticipant participant;
    private final Clock clock;

    public enum Action { REVIEW, REVOKE }
    public record Command(UUID requestId, UUID caseId, long expectedRevision, Action action) {
        public Command {
            if (invalid(requestId) || invalid(caseId) || expectedRevision < 0 || action == null)
                throw new IllegalArgumentException("Invalid verification command");
        }
    }
    public record Receipt(UUID caseId, long revision, boolean active) {}

    public VerifiedContributorService(EnabledAccountPairAuthority accounts,
            VerificationParticipant participant, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts);
        this.participant = Objects.requireNonNull(participant);
        this.clock = Objects.requireNonNull(clock);
    }

    public Receipt execute(UUID authenticatedReviewerId, Command command) {
        Objects.requireNonNull(command);
        if (invalid(authenticatedReviewerId)) throw new SecurityException("Verification denied");
        UUID target = participant.targetForCase(command.caseId());
        return accounts.withEnabledPair(authenticatedReviewerId, target,
                () -> participant.apply(authenticatedReviewerId, target, command, clock));
    }

    private static boolean invalid(UUID value) { return value == null || NIL.equals(value); }
}
