package com.routiqo.core.publiclive.privacy;

/** Different request or key attempted to reuse a person's nonrefundable pilot slot. */
public final class PilotPersonClaimConflict extends RuntimeException {
    public PilotPersonClaimConflict() { super("Pilot contribution unavailable"); }
}
