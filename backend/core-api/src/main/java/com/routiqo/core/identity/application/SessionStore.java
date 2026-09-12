package com.routiqo.core.identity.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SessionStore {
    void createChallenge(UUID id, String nonce, String bindingHash, Instant now, Instant expiresAt);
    String challengeNonce(UUID id, String bindingHash, Instant now);
    UUID exchange(UUID challengeId, String bindingHash, GoogleIdentityVerifier.Identity identity,
                  String tokenHash, Instant now, Instant expiresAt);
    UUID authenticate(String tokenHash, Instant now);
    record Renewal(UUID accountId, Instant expiresAt, boolean rotated) {}
    Renewal renew(String tokenHash, String replacementHash, Instant now);
    void deleteAccount(String tokenHash, Instant now);
    Optional<UUID> revocationAccount(String tokenHash);
    void revoke(String tokenHash, Instant now);
}
