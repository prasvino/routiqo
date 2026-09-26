package com.routiqo.core.moderation.application;

import java.util.Map;
import java.util.UUID;

/**
 * Restrict or restore an account's contributions from the Spots admin (ADR 0039/0041, ADR 0075),
 * through the audited restriction owner. The account comes from a resolved lookup reference, never
 * from the admin client.
 */
public final class ModeratorRestrictionService {
    private static final Map<String, AuditedContributionRestrictionService.Reason> REASONS = Map.of(
            "spam_manipulation", AuditedContributionRestrictionService.Reason.SPAM_MANIPULATION,
            "harassment", AuditedContributionRestrictionService.Reason.HARASSMENT,
            "unsafe_content", AuditedContributionRestrictionService.Reason.UNSAFE_CONTENT,
            "appeal_upheld", AuditedContributionRestrictionService.Reason.APPEAL_UPHELD,
            "error_correction", AuditedContributionRestrictionService.Reason.ERROR_CORRECTION);

    public record Result(boolean restricted, long revision, boolean replayed) {}

    /** The grant, revision or hourly quota refused the change; nothing was written. */
    public static final class Refused extends RuntimeException {
        public Refused() { super(null, null, false, false); }
    }

    private final AuditedContributionRestrictionService restrictions;

    public ModeratorRestrictionService(AuditedContributionRestrictionService restrictions) {
        this.restrictions = restrictions;
    }

    /** {@code recheck} runs inside the restriction transaction and throws SecurityException to refuse. */
    public Result apply(UUID operator, UUID requestId, UUID account, long expectedRevision, boolean restrict,
            String reason, Runnable recheck) {
        var mapped = reason == null ? null : REASONS.get(reason);
        if (mapped == null) throw new IllegalArgumentException("Invalid restriction reason");
        final AuditedContributionRestrictionService.Command command;
        try {
            command = new AuditedContributionRestrictionService.Command(requestId, account, expectedRevision,
                    restrict ? AuditedContributionRestrictionService.Action.RESTRICT
                            : AuditedContributionRestrictionService.Action.RESTORE, mapped);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid restriction request");
        }
        try {
            var receipt = restrictions.execute(operator, command, recheck);
            return new Result(receipt.restricted(), receipt.afterRevision(), false);
        } catch (SecurityException refused) {
            throw new Refused();
        }
    }
}
