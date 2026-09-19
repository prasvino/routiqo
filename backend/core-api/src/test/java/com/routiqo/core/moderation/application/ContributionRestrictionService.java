package com.routiqo.core.moderation.application;

import com.routiqo.core.identity.application.AccountWriteAuthority;
import com.routiqo.core.moderation.domain.ContributorAssessment;
import java.util.Objects;
import java.util.UUID;

/** Test-only setup helper for legacy signal persistence scenarios. */
public final class ContributionRestrictionService {
    private final AccountWriteAuthority accounts;
    private final ContributionRestrictionParticipant participant;

    public ContributionRestrictionService(AccountWriteAuthority accounts,
            ContributionRestrictionParticipant participant) {
        this.accounts = Objects.requireNonNull(accounts);
        this.participant = Objects.requireNonNull(participant);
    }

    public ContributorAssessment suspend(UUID actorId, long expectedRevision) {
        return accounts.withEnabledAccount(actorId, () -> {
            ContributorAssessment prior = participant.read(actorId);
            ContributorAssessment next = prior.suspend(expectedRevision);
            if (next != prior) participant.replace(prior, next);
            return next;
        });
    }

    public ContributorAssessment unsuspend(UUID actorId, long expectedRevision) {
        return accounts.withEnabledAccount(actorId, () -> {
            ContributorAssessment prior = participant.read(actorId);
            ContributorAssessment next = prior.unsuspend(expectedRevision);
            participant.replace(prior, next);
            return next;
        });
    }
}
