package com.routiqo.core.publiclive.application;

/** A person slot or exact retry identity was already consumed differently. */
public final class FrozenPublicShareConflict extends RuntimeException {
    public FrozenPublicShareConflict() { super("Frozen public Share unavailable"); }
}
