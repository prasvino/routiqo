package com.routiqo.core.spot.application;

/** A spent budget; {@code retryAfterSeconds} is when the client may usefully try again. */
public final class SpotsRateLimited extends RuntimeException {
    private final long retryAfterSeconds;

    public SpotsRateLimited() { this(60); }

    public SpotsRateLimited(long retryAfterSeconds) {
        super(null, null, false, false);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() { return retryAfterSeconds; }
}
