package com.routiqo.core.moderation.application;

import com.routiqo.core.identity.application.EnabledAccountPairAuthority;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Internal operator-authenticated moderation boundary. It is not an HTTP authentication adapter. */
public final class AuditedContributionRestrictionService {
    private static final UUID NIL = new UUID(0, 0);
    private final EnabledAccountPairAuthority accounts;
    private final AuditedContributionRestrictionParticipant participant;
    private final Clock clock;

    public enum Action { RESTRICT, RESTORE }
    public enum Reason {
        SPAM_MANIPULATION, HARASSMENT, UNSAFE_CONTENT, APPEAL_UPHELD, ERROR_CORRECTION
    }

    public record Command(UUID requestId, UUID targetId, long expectedRevision,
            Action action, Reason reason) {
        public Command {
            if (invalid(requestId) || invalid(targetId) || expectedRevision < 0
                    || action == null || reason == null || !compatible(action, reason)) {
                throw new IllegalArgumentException("Invalid audited restriction command");
            }
        }

        private static boolean compatible(Action action, Reason reason) {
            return action == Action.RESTRICT
                    ? reason == Reason.SPAM_MANIPULATION || reason == Reason.HARASSMENT
                            || reason == Reason.UNSAFE_CONTENT
                    : reason == Reason.APPEAL_UPHELD || reason == Reason.ERROR_CORRECTION;
        }

        @Override public String toString() { return "AuditedRestrictionCommand[private]"; }
    }

    public record Receipt(UUID requestId, UUID targetId, long beforeRevision,
            long afterRevision, boolean restricted, Action action, Reason reason,
            Instant issuedAt, Instant expiresAt) {
        public Receipt {
            if (invalid(requestId) || invalid(targetId) || beforeRevision < 0
                    || beforeRevision == Long.MAX_VALUE || afterRevision != beforeRevision + 1
                    || action == null || reason == null
                    || !Command.compatible(action, reason)
                    || restricted != (action == Action.RESTRICT)
                    || !canonical(issuedAt) || !canonical(expiresAt)
                    || !Duration.ofDays(30).equals(Duration.between(issuedAt, expiresAt))) {
                throw new IllegalArgumentException("Invalid audited restriction receipt");
            }
        }

        @Override public String toString() { return "AuditedRestrictionReceipt[private]"; }
    }

    public AuditedContributionRestrictionService(EnabledAccountPairAuthority accounts,
            AuditedContributionRestrictionParticipant participant, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts);
        this.participant = Objects.requireNonNull(participant);
        this.clock = Objects.requireNonNull(clock);
    }

    public Receipt execute(UUID authenticatedOperatorId, Command command) {
        Objects.requireNonNull(command);
        return accounts.withEnabledPair(authenticatedOperatorId, command.targetId(),
                () -> participant.apply(authenticatedOperatorId, command, clock));
    }

    private static boolean invalid(UUID value) { return value == null || NIL.equals(value); }

    private static boolean canonical(Instant value) {
        return value != null && !value.equals(Instant.MIN) && !value.equals(Instant.MAX)
                && value.getNano() % 1_000 == 0;
    }
}
