package com.routiqo.core.moderation.application;

import com.routiqo.core.identity.application.EnabledAccountPairAuthority;
import com.routiqo.core.moderation.domain.DirectionalBlock;
import java.util.Objects;
import java.util.UUID;

/** Internal block intent and bilateral decision. Target authorization is a future API gate. */
public final class DurableBlockPolicyService {
    private static final int MAX_OUTGOING_EDGES = 100;
    private final EnabledAccountPairAuthority accounts;
    private final DirectionalBlockParticipant edges;

    /** Current transaction snapshot only; not a reusable publication or delivery capability. */
    public enum PairDecision { CLEAR, EXCLUDED }

    public DurableBlockPolicyService(EnabledAccountPairAuthority accounts,
            DirectionalBlockParticipant edges) {
        this.accounts = Objects.requireNonNull(accounts);
        this.edges = Objects.requireNonNull(edges);
    }

    public DirectionalBlock block(UUID blockerId, UUID targetId, long expectedRevision) {
        return accounts.withEnabledPair(blockerId, targetId, () -> {
            DirectionalBlock prior = checkedRead(blockerId, targetId);
            DirectionalBlock updated = prior.block(expectedRevision);
            if (updated == prior) return prior;
            checkCapacityForNewEdge(prior);
            edges.replace(prior, updated);
            return updated;
        });
    }

    public DirectionalBlock unblock(UUID blockerId, UUID targetId, long expectedRevision) {
        return accounts.withEnabledPair(blockerId, targetId, () -> {
            DirectionalBlock prior = checkedRead(blockerId, targetId);
            DirectionalBlock updated = prior.unblock(expectedRevision);
            checkCapacityForNewEdge(prior);
            edges.replace(prior, updated);
            return updated;
        });
    }

    /** Snapshot decision for internal composition; later delivery must recheck its own authority. */
    public PairDecision evaluate(UUID firstId, UUID secondId) {
        return accounts.withEnabledPair(firstId, secondId, () -> {
            DirectionalBlock first = checkedRead(firstId, secondId);
            DirectionalBlock second = checkedRead(secondId, firstId);
            return first.clearWith(second) ? PairDecision.CLEAR : PairDecision.EXCLUDED;
        });
    }

    private void checkCapacityForNewEdge(DirectionalBlock prior) {
        if (prior.revision() == 0
                && edges.outgoingCount(prior.blockerId()) >= MAX_OUTGOING_EDGES) {
            throw new BlockEdgeCapacityExceeded();
        }
    }

    private DirectionalBlock checkedRead(UUID blockerId, UUID targetId) {
        DirectionalBlock edge = edges.read(blockerId, targetId);
        if (edge == null || !blockerId.equals(edge.blockerId())
                || !targetId.equals(edge.targetId())) {
            throw new IllegalStateException("Block edge authority unavailable");
        }
        return edge;
    }
}
