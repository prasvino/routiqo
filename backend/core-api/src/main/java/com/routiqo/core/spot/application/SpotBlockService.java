package com.routiqo.core.spot.application;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.moderation.application.BlockEdgeCapacityExceeded;
import com.routiqo.core.moderation.application.DurableBlockPolicyService;
import java.util.Objects;
import java.util.UUID;

/**
 * Block the author of a Spot post (POSTS_AND_SIGNALS_SPEC, ADR 0072). The author is resolved on the
 * server and never revealed; the author is not told. Signal summaries are unattributed, so they
 * cannot be a block target. There is no unblock in the pilot.
 */
public final class SpotBlockService {
    static final String RATE_CATEGORY = "spot-block-account";
    static final int RATE_LIMIT = 10;

    private final AuthRateGate rates;
    private final SpotReportStore store;
    private final DurableBlockPolicyService blocks;

    public SpotBlockService(AuthRateGate rates, SpotReportStore store, DurableBlockPolicyService blocks) {
        this.rates = Objects.requireNonNull(rates);
        this.store = Objects.requireNonNull(store);
        this.blocks = Objects.requireNonNull(blocks);
    }

    public void blockAuthor(UUID actor, UUID ref) {
        if (actor == null || ref == null || (ref.getMostSignificantBits() == 0 && ref.getLeastSignificantBits() == 0))
            throw new IllegalArgumentException("Invalid identifier");
        final boolean allowed;
        try {
            allowed = rates.allow(actor.toString(), RATE_CATEGORY, RATE_LIMIT);
        } catch (RuntimeException unavailable) {
            throw new SpotsUnavailable();
        }
        if (!allowed) throw new SpotsRateLimited();
        UUID author = store.postAuthor(ref).orElseThrow(SpotContributionNotFound::new);
        if (author.equals(actor)) throw new SpotContributionForbidden();
        try {
            blocks.ensureBlocked(actor, author);
        } catch (BlockEdgeCapacityExceeded full) {
            throw new SpotContributionConflict();
        } catch (SecurityException authorUnavailable) {
            // The pair authority refuses a disabled account; for the blocker the post is simply gone.
            throw new SpotContributionNotFound();
        }
    }
}
