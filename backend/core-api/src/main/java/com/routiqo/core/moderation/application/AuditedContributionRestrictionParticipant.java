package com.routiqo.core.moderation.application;

import java.time.Clock;
import java.util.UUID;

/** Persistence participant called only inside ordered enabled-account pair authority. */
public interface AuditedContributionRestrictionParticipant {
    AuditedContributionRestrictionService.Receipt apply(
            UUID operatorId,
            AuditedContributionRestrictionService.Command command,
            Clock clock);
}
