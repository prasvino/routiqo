package com.routiqo.core.spot.application;

public final class SpotsRateLimited extends RuntimeException {
    public SpotsRateLimited() { super(null, null, false, false); }
}
