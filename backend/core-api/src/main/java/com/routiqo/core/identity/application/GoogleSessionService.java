package com.routiqo.core.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Internal orchestration only; transport must enforce origin/CSRF and bind secrets to the initiating device. */
public final class GoogleSessionService {
    private final SessionStore store;
    private final GoogleIdentityVerifier verifier;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    public GoogleSessionService(SessionStore store, GoogleIdentityVerifier verifier, Clock clock) {
        this.store = Objects.requireNonNull(store); this.verifier = Objects.requireNonNull(verifier);
        this.clock = Objects.requireNonNull(clock);
    }
    public record Challenge(UUID id, String nonce, String binding, Instant expiresAt) {
        @Override public String toString() { return "Challenge[redacted]"; }
    }
    public record Session(UUID accountId, String credential, Instant expiresAt) {
        @Override public String toString() { return "Session[redacted]"; }
    }
    public Challenge begin() {
        Instant now = now(); var id = UUID.randomUUID(); String nonce = secret(), binding = secret();
        Instant expires = now.plusSeconds(300);
        store.createChallenge(id, nonce, digest(binding), now, expires);
        return new Challenge(id, nonce, binding, expires);
    }
    public Session exchange(UUID challengeId, String binding, String googleToken) {
        if (challengeId == null) throw denied();
        String bindingHash = digest(binding);
        String nonce = store.challengeNonce(challengeId, bindingHash, now());
        var identity = verifier.verify(googleToken, nonce); // Never inside a database transaction.
        Instant now = now(), expires = now.plusSeconds(900);
        String credential = secret();
        UUID account = store.exchange(challengeId, bindingHash, identity, digest(credential), now, expires);
        return new Session(account, credential, expires);
    }
    public UUID authenticate(String credential) { return store.authenticate(digest(credential), now()); }
    public Session renew(String credential) {
        String replacement = secret();
        var result = store.renew(digest(credential), digest(replacement), now());
        return new Session(result.accountId(), result.rotated() ? replacement : credential, result.expiresAt());
    }
    public void revoke(String credential) { store.revoke(digest(credential), now()); }
    public void deleteAccount(String credential) { store.deleteAccount(digest(credential), now()); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private String secret() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static String digest(String secret) {
        if (secret == null || !secret.matches("[A-Za-z0-9_-]{43}")) throw denied();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static SecurityException denied() { return new SecurityException("Authentication could not be completed"); }
}
