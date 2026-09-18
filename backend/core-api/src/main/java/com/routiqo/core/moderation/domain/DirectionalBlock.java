package com.routiqo.core.moderation.domain;

import java.util.UUID;

/** Private directed edge; both directions need explicit fresh authority to be clear. */
public record DirectionalBlock(UUID blockerId, UUID targetId, long revision, boolean blocked) {
    private static final UUID NIL = new UUID(0, 0);

    public DirectionalBlock {
        if (invalid(blockerId) || invalid(targetId) || blockerId.equals(targetId)
                || revision < 0 || revision == Long.MAX_VALUE && !blocked) {
            throw new IllegalArgumentException("Invalid directional block");
        }
    }

    public static DirectionalBlock initial(UUID blockerId, UUID targetId) {
        return new DirectionalBlock(blockerId, targetId, 0, false);
    }

    public DirectionalBlock block(long expectedRevision) {
        requireNotFuture(expectedRevision);
        if (revision == Long.MAX_VALUE) return this;
        return new DirectionalBlock(blockerId, targetId, revision + 1, true);
    }

    public DirectionalBlock unblock(long expectedRevision) {
        requireNotFuture(expectedRevision);
        if (expectedRevision != revision || revision >= Long.MAX_VALUE - 1) throw denied();
        return new DirectionalBlock(blockerId, targetId, revision + 1, false);
    }

    /** Unknown reverse state fails closed. An unrelated edge is a programming error. */
    public boolean clearWith(DirectionalBlock reverse) {
        if (reverse == null) return false;
        if (!blockerId.equals(reverse.targetId) || !targetId.equals(reverse.blockerId)) {
            throw new IllegalArgumentException("Invalid reverse block edge");
        }
        return !blocked && !reverse.blocked;
    }

    private void requireNotFuture(long expected) {
        if (expected < 0 || expected > revision) throw denied();
    }
    private static boolean invalid(UUID id) { return id == null || NIL.equals(id); }
    private static IllegalStateException denied() {
        return new IllegalStateException("Directional block transition denied");
    }
    @Override public String toString() { return "DirectionalBlock[private]"; }
}
