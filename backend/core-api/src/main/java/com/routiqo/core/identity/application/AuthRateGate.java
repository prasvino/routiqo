package com.routiqo.core.identity.application;

public interface AuthRateGate {
    boolean allow(String peerAddress, String category, int limit);
}
