package com.routiqo.core.identity.infrastructure;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.*;
import java.util.Date;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import static org.assertj.core.api.Assertions.*;

class GoogleTokenVerifierTest {
    static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");
    static final String CLIENT = "test-client.apps.googleusercontent.com";
    static final String NONCE = "test-nonce-for-local-signature-verification-only";
    static final RSAKey KEY;
    static { try { KEY = new RSAKeyGenerator(2048).generate(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    GoogleTokenVerifier verifier() throws Exception {
        return new GoogleTokenVerifier(NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build(),
                CLIENT, Clock.fixed(NOW, ZoneOffset.UTC));
    }
    String token(Consumer<JWTClaimsSet.Builder> change, RSAKey key, JWSAlgorithm algorithm) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("https://accounts.google.com").subject("google-subject-123")
                .audience(CLIENT).issueTime(Date.from(NOW.minusSeconds(30)))
                .expirationTime(Date.from(NOW.plusSeconds(300))).claim("nonce", NONCE);
        change.accept(claims);
        var token = new SignedJWT(new JWSHeader(algorithm), claims.build());
        token.sign(new RSASSASigner(key)); return token.serialize();
    }
    String token(Consumer<JWTClaimsSet.Builder> change) throws Exception { return token(change, KEY, JWSAlgorithm.RS256); }
    @Test void verifiesSignatureAndReturnsOnlyStableIdentity() throws Exception {
        var identity = verifier().verify(token(c -> c.claim("email", "untrusted@example.invalid")), NONCE);
        assertThat(identity.provider()).isEqualTo("google");
        assertThat(identity.subject()).isEqualTo("google-subject-123");
        assertThat(verifier().verify(token(c -> c.issuer("accounts.google.com")), NONCE)).isEqualTo(identity);
    }
    @Test void rejectsWrongAudienceIssuerPresenterAndMissingSubject() throws Exception {
        for (Consumer<JWTClaimsSet.Builder> change : java.util.List.<Consumer<JWTClaimsSet.Builder>>of(
                c -> c.audience("another-client"), c -> c.issuer("https://attacker.invalid"),
                c -> c.claim("azp", "another-client"), c -> c.subject(null))) {
            String raw = token(change);
            assertThatThrownBy(() -> verifier().verify(raw, NONCE)).isInstanceOf(SecurityException.class);
        }
    }
    @Test void rejectsExpiredFutureAndMissingTimes() throws Exception {
        for (Consumer<JWTClaimsSet.Builder> change : java.util.List.<Consumer<JWTClaimsSet.Builder>>of(
                c -> c.expirationTime(Date.from(NOW)), c -> c.issueTime(Date.from(NOW.plusSeconds(120))),
                c -> c.expirationTime(null), c -> c.issueTime(null),
                c -> c.notBeforeTime(Date.from(NOW.plusSeconds(120))))) {
            String raw = token(change);
            assertThatThrownBy(() -> verifier().verify(raw, NONCE)).isInstanceOf(SecurityException.class);
        }
    }
    @Test void rejectsWrongSignatureAlgorithmAndNonce() throws Exception {
        String wrongKey = token(c -> {}, new RSAKeyGenerator(2048).generate(), JWSAlgorithm.RS256);
        String wrongAlgorithm = token(c -> {}, KEY, JWSAlgorithm.RS512);
        String wrongNonce = token(c -> c.claim("nonce", "another-nonce"));
        for (String raw : java.util.List.of(wrongKey, wrongAlgorithm, wrongNonce, "not-a-token"))
            assertThatThrownBy(() -> verifier().verify(raw, NONCE)).isInstanceOf(SecurityException.class)
                    .hasMessage("Google sign-in could not be verified");
    }
    @Test void requiresConfiguredClientAndServerChallenge() throws Exception {
        assertThatThrownBy(() -> new GoogleTokenVerifier(NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build(), "",
                Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
        String raw = token(c -> {});
        assertThatThrownBy(() -> verifier().verify(raw, null)).isInstanceOf(SecurityException.class);
    }
}
