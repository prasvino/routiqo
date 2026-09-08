package com.routiqo.core.journey.application;

public final class JourneyConflict extends RuntimeException {
    public JourneyConflict() { super("Journey could not be started with these details"); }
}
