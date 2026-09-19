package com.routiqo.core.moderation.application;

import com.routiqo.core.moderation.domain.ContributorAssessment;

/** Trusted same-transaction participant. Caller holds the enabled target-account lock. */
public interface ContributionRestrictionParticipant extends ContributionRestrictionReader {
    void replace(ContributorAssessment prior, ContributorAssessment updated);
}
