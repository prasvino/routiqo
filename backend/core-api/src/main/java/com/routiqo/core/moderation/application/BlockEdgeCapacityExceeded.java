package com.routiqo.core.moderation.application;

/** Generic bounded-state denial; no identities or edge contents appear in diagnostics. */
public final class BlockEdgeCapacityExceeded extends RuntimeException {
    public BlockEdgeCapacityExceeded() { super("Block edge capacity reached"); }
}
