package com.routiqo.core.identity.application;

public interface GoogleIdentityVerifier {
    /** Nonce must come from an unexpired server-owned challenge, consumed once by the caller. */
    Identity verify(String idToken, String expectedNonce);
    record Identity(String provider, String subject) {}
}
