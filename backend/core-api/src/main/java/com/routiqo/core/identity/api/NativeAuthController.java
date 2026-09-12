package com.routiqo.core.identity.api;

import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.RecentAuthenticationRequired;
import com.routiqo.core.security.NativeAuthGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/native/auth")
@Profile("native-auth")
public class NativeAuthController {
    private static final Set<String> EXCHANGE_FIELDS = Set.of("challengeId", "binding", "idToken");
    private static final Set<String> DELETE_FIELDS = Set.of("confirmation", "accountId");
    private final GoogleSessionService sessions;
    private final AuthRateGate rates;

    public NativeAuthController(GoogleSessionService sessions, AuthRateGate rates) {
        this.sessions = sessions;
        this.rates = rates;
    }

    public record NativeChallengeResponse(UUID id, String nonce, String binding, Instant expiresAt) {
        @Override public String toString() { return "NativeChallengeResponse[redacted]"; }
    }
    public record NativeSessionResponse(UUID accountId, String credential, Instant expiresAt) {
        @Override public String toString() { return "NativeSessionResponse[redacted]"; }
    }
    public record NativeAccountResponse(UUID accountId) {}

    @PostMapping("/google/challenge") NativeChallengeResponse challenge(HttpServletRequest request) {
        NativeAuthJson.emptyObject(request);
        var challenge = sessions.begin();
        return new NativeChallengeResponse(challenge.id(), challenge.nonce(), challenge.binding(), challenge.expiresAt());
    }

    @PostMapping("/google/exchange") NativeSessionResponse exchange(HttpServletRequest request) {
        var input = NativeAuthJson.object(request, EXCHANGE_FIELDS);
        UUID challengeId = NativeAuthJson.uuid(input, "challengeId");
        String binding = NativeAuthJson.text(input, "binding", 43);
        if (!binding.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid binding");
        String idToken = NativeAuthJson.text(input, "idToken", 16_384);
        if (!rates.allow(challengeId.toString(), "native-exchange-challenge", 20))
            return limited();
        return response(sessions.exchange(challengeId, binding, idToken));
    }

    @GetMapping("/session") NativeAccountResponse session(HttpServletRequest request) {
        return new NativeAccountResponse(sessions.authenticate(credential(request)));
    }

    @PostMapping("/session/renew") NativeSessionResponse renew(HttpServletRequest request) {
        NativeAuthJson.emptyObject(request);
        return response(sessions.renew(credential(request)));
    }

    @PostMapping("/logout") ResponseEntity<Void> logout(HttpServletRequest request) {
        NativeAuthJson.emptyObject(request);
        sessions.revoke(credential(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/account/delete") ResponseEntity<Void> deleteAccount(HttpServletRequest request) {
        var input = NativeAuthJson.object(request, DELETE_FIELDS);
        if (!"DELETE".equals(NativeAuthJson.text(input, "confirmation", 6)))
            throw new IllegalArgumentException("Explicit confirmation required");
        UUID accountId = NativeAuthJson.uuid(input, "accountId");
        String credential = credential(request);
        if (!accountId.equals(sessions.authenticate(credential))) throw new SecurityException("Account changed");
        sessions.deleteAccount(credential);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(RecentAuthenticationRequired.class)
    ResponseEntity<Void> recentAuthentication() { return ResponseEntity.status(428).build(); }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Void> denied() { return ResponseEntity.status(401).build(); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Void> malformed() { return ResponseEntity.badRequest().build(); }

    private static NativeSessionResponse limited() { throw new NativeAuthRateLimited(); }

    @ExceptionHandler(NativeAuthRateLimited.class)
    ResponseEntity<Void> limitedResponse() {
        return ResponseEntity.status(429).header("Retry-After", "60").build();
    }

    private static NativeSessionResponse response(GoogleSessionService.Session session) {
        return new NativeSessionResponse(session.accountId(), session.credential(), session.expiresAt());
    }

    private static String credential(HttpServletRequest request) {
        Object credential = request.getAttribute(NativeAuthGuard.CREDENTIAL_ATTRIBUTE);
        if (!(credential instanceof String value)) throw new SecurityException("Authentication could not be completed");
        return value;
    }

    private static final class NativeAuthRateLimited extends RuntimeException {}
}
