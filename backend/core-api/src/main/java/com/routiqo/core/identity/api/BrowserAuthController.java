package com.routiqo.core.identity.api;

import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.security.BrowserAuthPolicy;
import java.time.Instant;
import java.util.UUID;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@Profile("web-auth")
public class BrowserAuthController {
    private final GoogleSessionService sessions;
    private final BrowserAuthPolicy policy;
    public BrowserAuthController(GoogleSessionService sessions, BrowserAuthPolicy policy) { this.sessions = sessions; this.policy = policy; }
    public record CsrfResponse(String token) {}
    public record ChallengeResponse(UUID id, String nonce, Instant expiresAt) {}
    public record ExchangeRequest(UUID challengeId, String idToken) {
        @Override public String toString() { return "ExchangeRequest[redacted]"; }
    }
    public record SessionResponse(UUID accountId, Instant expiresAt) {}
    public record AccountResponse(UUID accountId) {}
    public record DeleteAccountRequest(String confirmation, UUID accountId) {}
    @PostMapping("/account/delete") ResponseEntity<Void> deleteAccount(@RequestBody DeleteAccountRequest input,
            HttpServletRequest request, HttpServletResponse response) {
        if (!"DELETE".equals(input.confirmation()) || input.accountId() == null)
            throw new IllegalArgumentException("Explicit confirmation required");
        String credential = cookie(request, "routiqo_session");
        if (!input.accountId().equals(sessions.authenticate(credential))) throw new SecurityException("Account changed");
        sessions.deleteAccount(credential);
        response.addHeader(HttpHeaders.SET_COOKIE, policy.cookie("routiqo_session", "", 0));
        response.addHeader(HttpHeaders.SET_COOKIE, policy.cookie("routiqo_binding", "", 0));
        return ResponseEntity.noContent().build();
    }
    @ExceptionHandler(com.routiqo.core.identity.application.RecentAuthenticationRequired.class)
    ResponseEntity<Void> recentAuthentication() { return ResponseEntity.status(428).build(); }
    @GetMapping("/csrf") CsrfResponse csrf(CsrfToken token) { return new CsrfResponse(token.getToken()); }
    @PostMapping("/google/challenge") ChallengeResponse challenge(HttpServletResponse response) {
        var challenge = sessions.begin();
        response.addHeader(HttpHeaders.SET_COOKIE, policy.cookie("routiqo_binding", challenge.binding(), 300));
        return new ChallengeResponse(challenge.id(), challenge.nonce(), challenge.expiresAt());
    }
    @PostMapping("/google/exchange") SessionResponse exchange(@RequestBody ExchangeRequest input,
            HttpServletRequest request, HttpServletResponse response) {
        if (input.challengeId() == null || input.idToken() == null || input.idToken().isBlank() || input.idToken().length() > 16384)
            throw new IllegalArgumentException("Invalid exchange request");
        var session = sessions.exchange(input.challengeId(), cookie(request, "routiqo_binding"), input.idToken());
        response.addHeader(HttpHeaders.SET_COOKIE, policy.cookie("routiqo_session", session.credential(), 900));
        response.addHeader(HttpHeaders.SET_COOKIE, policy.cookie("routiqo_binding", "", 0));
        return new SessionResponse(session.accountId(), session.expiresAt());
    }
    @GetMapping("/session") AccountResponse session(HttpServletRequest request) {
        return new AccountResponse(sessions.authenticate(cookie(request, "routiqo_session")));
    }
    @PostMapping("/session/renew") SessionResponse renew(HttpServletRequest request, HttpServletResponse response) {
        var session = sessions.renew(cookie(request, "routiqo_session"));
        long remaining = Math.max(0, Math.min(900, java.time.Duration.between(Instant.now(), session.expiresAt()).getSeconds()));
        response.addHeader(HttpHeaders.SET_COOKIE, policy.cookie("routiqo_session", session.credential(), remaining));
        return new SessionResponse(session.accountId(), session.expiresAt());
    }
    @PostMapping("/logout") ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String credential = cookie(request, "routiqo_session");
        if (credential != null) {
            try { sessions.revoke(credential); } catch (SecurityException malformedCookie) { /* Clear malformed cookies too. */ }
        }
        response.addHeader(HttpHeaders.SET_COOKIE, policy.cookie("routiqo_session", "", 0));
        response.addHeader(HttpHeaders.SET_COOKIE, policy.cookie("routiqo_binding", "", 0));
        return ResponseEntity.noContent().build();
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<Void> denied() { return ResponseEntity.status(401).build(); }
    private String cookie(HttpServletRequest request, String name) {
        return com.routiqo.core.security.BrowserCookies.read(request, policy, name);
    }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<Void> malformed() { return ResponseEntity.badRequest().build(); }
}
