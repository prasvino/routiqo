package com.routiqo.core.publiclive.application;

/** Another public-purpose intent occupies this verified person's fixed window. */
public final class PublicSignalIntentConflict extends RuntimeException {
    public PublicSignalIntentConflict() { super("Public share unavailable"); }
}
