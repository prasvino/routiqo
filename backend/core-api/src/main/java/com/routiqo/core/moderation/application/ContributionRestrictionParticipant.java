package com.routiqo.core.moderation.application;

import com.routiqo.core.moderation.domain.ContributorAssessment;
import java.util.UUID;

/** Trusted same-transaction participant. Caller holds the enabled target-account lock. */
public interface ContributionRestrictionParticipant {
    /** Missing row means UNASSESSED only under an enabled-account authority transaction. */
    ContributorAssessment read(UUID actorId);

    void replace(ContributorAssessment prior, ContributorAssessment updated);
}
