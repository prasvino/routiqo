package com.routiqo.core.spot.application;

public final class SpotsUnavailable extends RuntimeException {
    public SpotsUnavailable() { super(null, null, false, false); }
}
