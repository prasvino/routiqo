package com.routiqo.core.identity.infrastructure;

import com.routiqo.core.identity.application.GoogleIdentityVerifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

public final class GoogleTokenVerifier implements GoogleIdentityVerifier {
    private final NimbusJwtDecoder decoder;
    public GoogleTokenVerifier(NimbusJwtDecoder decoder, String clientId, Clock clock) {
        this.decoder = Objects.requireNonNull(decoder);
        Objects.requireNonNull(clock);
        if (clientId == null || !clientId.matches("[A-Za-z0-9-]{1,200}\\.apps\\.googleusercontent\\.com"))
            throw new IllegalArgumentException("A Google OAuth client ID is required");
        decoder.setJwtValidator(jwt -> validClaims(jwt, clientId, clock.instant())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Google credential rejected", null)));
    }

    private static boolean validClaims(Jwt jwt, String clientId, Instant now) {
        try {
            String issuer = jwt.getClaimAsString("iss");
            String subject = jwt.getSubject();
            String presenter = jwt.getClaimAsString("azp");
            List<String> audience = jwt.getAudience();
            Instant issued = jwt.getIssuedAt(), expiry = jwt.getExpiresAt(), notBefore = jwt.getNotBefore();
            return ("https://accounts.google.com".equals(issuer) || "accounts.google.com".equals(issuer))
                    && audience != null && audience.size() == 1 && audience.contains(clientId)
                    && (presenter == null || clientId.equals(presenter))
                    && subject != null && subject.matches("[A-Za-z0-9_-]{1,255}")
                    && issued != null && expiry != null && expiry.isAfter(issued)
                    && !issued.isAfter(now.plusSeconds(60)) && expiry.isAfter(now)
                    && (notBefore == null || !notBefore.isAfter(now.plusSeconds(60)));
        } catch (RuntimeException malformedClaim) { return false; }
    }

    @Override public Identity verify(String idToken, String expectedNonce) {
        if (idToken == null || idToken.length() > 16_384 || expectedNonce == null
                || !expectedNonce.matches("[A-Za-z0-9_-]{32,128}")) throw rejected();
        try {
            Jwt jwt = decoder.decode(idToken);
            String nonce = jwt.getClaimAsString("nonce");
            if (nonce == null || !MessageDigest.isEqual(nonce.getBytes(StandardCharsets.UTF_8),
                    expectedNonce.getBytes(StandardCharsets.UTF_8))) throw rejected();
            return new Identity("google", jwt.getSubject());
        } catch (JwtException | IllegalArgumentException invalid) {
            // Do not leak or log raw provider credentials or parsing details.
            throw rejected();
        }
    }
    private static SecurityException rejected() { return new SecurityException("Google sign-in could not be verified"); }
}
