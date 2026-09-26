package com.routiqo.core.spot.application;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.moderation.application.BlockEdgeCapacityExceeded;
import com.routiqo.core.moderation.application.DurableBlockPolicyService;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Block the author of a current Spot post (POSTS_AND_SIGNALS_SPEC, ADR 0072). Two effects:
 * <ul>
 *   <li>the account-level block edge (ADR 0040), for later Ask Ahead and chat delivery;</li>
 *   <li>the post's alias hidden from the blocker in that room only. Hiding the author's posts in
 *       other rooms, or their votes, would let a blocker link aliases across rooms.</li>
 * </ul>
 * The author is resolved on the server, never revealed and not told. There is no unblock in the pilot.
 */
public final class SpotBlockService {
    static final String RATE_CATEGORY = "spot-block-account";
    static final int RATE_LIMIT = 10;

    private final AuthRateGate rates;
    private final SpotReportStore store;
    private final DurableBlockPolicyService blocks;
    private final Clock clock;

    public SpotBlockService(AuthRateGate rates, SpotReportStore store, DurableBlockPolicyService blocks,
            Clock clock) {
        this.rates = Objects.requireNonNull(rates);
        this.store = Objects.requireNonNull(store);
        this.blocks = Objects.requireNonNull(blocks);
        this.clock = Objects.requireNonNull(clock);
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
        var now = clock.instant();
        SpotReportStore.BlockablePost post = store.blockablePost(ref, now).orElseThrow(SpotContributionNotFound::new);
        if (post.authorId().equals(actor)) throw new SpotContributionForbidden();
        try {
            blocks.ensureBlocked(actor, post.authorId());
        } catch (BlockEdgeCapacityExceeded full) {
            throw new SpotContributionConflict();
        } catch (SecurityException authorUnavailable) {
            // A disabled author gets no edge; the answer stays the same so it reveals nothing.
            // The actor was authenticated by the guard just now.
        } catch (IllegalStateException unavailable) {
            throw new SpotsUnavailable();
        }
        store.hideAlias(actor, post.spotId(), post.roomDay(), post.alias(), now);
    }
}
