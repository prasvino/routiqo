package com.routiqo.core.moderation.application;

import com.routiqo.core.moderation.domain.ContributorAssessment;
import java.util.UUID;

/** Current same-transaction restriction snapshot; it grants no mutation authority. */
public interface ContributionRestrictionReader {
    /** Missing row means UNASSESSED only under an enabled-account authority transaction. */
    ContributorAssessment read(UUID actorId);
}
