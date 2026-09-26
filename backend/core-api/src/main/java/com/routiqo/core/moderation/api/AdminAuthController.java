package com.routiqo.core.moderation.api;

import com.routiqo.core.moderation.infrastructure.AdminSessionService;
import com.routiqo.core.security.BrowserAuthPolicy;
import com.routiqo.core.security.BrowserCookies;
import com.routiqo.core.security.ConditionalOnExactlyTrue;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin sign-in, renewable session and logout, shared by every admin feature (ADR 0075). */
@RestController
@RequestMapping("/api/v1/admin/auth")
@Profile("web-auth & persistence & google-auth")
@ConditionalOnExactlyTrue({"ROUTIQO_ADMIN_ENABLED"})
public final class AdminAuthController {
    private final AdminSessionService sessions;
    private final BrowserAuthPolicy policy;
    public AdminAuthController(AdminSessionService sessions, AdminAccessConfiguration.AdminSettings settings) {
        this.sessions = sessions; this.policy = settings.policy();
    }
    public record CsrfResponse(String token) {}
    public record ChallengeResponse(UUID id, String nonce, Instant expiresAt) {}
    public record SessionResponse(UUID accountId, Instant expiresAt, Instant absoluteExpiresAt) {}

    @GetMapping("/csrf") CsrfResponse csrf(CsrfToken token) { return new CsrfResponse(token.getToken()); }
    @PostMapping("/google/challenge") ChallengeResponse challenge(HttpServletRequest request, HttpServletResponse response) {
        AdminHttp.emptyBody(request);
        var value = sessions.begin();
        response.addHeader(HttpHeaders.SET_COOKIE, policy.cookie("routiqo_admin_binding", value.binding(), 300));
        return new ChallengeResponse(value.id(), value.nonce(), value.expiresAt());
    }
    @PostMapping("/google/exchange") SessionResponse exchange(HttpServletRequest request, HttpServletResponse response) {
        var input = AdminTrafficJson.exchange(request);
        var value = sessions.exchange(input.challengeId(), cookie(request, "routiqo_admin_binding"), input.idToken());
        response.addHeader(HttpHeaders.SET_COOKIE, policy.cookie("routiqo_admin_session", value.credential(), 900));
        response.addHeader(HttpHeaders.SET_COOKIE, policy.cookie("routiqo_admin_binding", "", 0));
        return new SessionResponse(value.accountId(), value.expiresAt(), value.absoluteExpiresAt());
    }
    @GetMapping("/session") SessionResponse session(HttpServletRequest request) {
        AdminHttp.noQuery(request);
        var value = sessions.authenticate(cookie(request, "routiqo_admin_session"));
        return new SessionResponse(value.accountId(), value.expiresAt(), value.absoluteExpiresAt());
    }
    /** Called by the admin app while the operator is active; never past 8 hours from sign-in. */
    @PostMapping("/session/renew") SessionResponse renew(HttpServletRequest request, HttpServletResponse response) {
        AdminHttp.noQuery(request);
        AdminHttp.emptyBody(request);
        String credential = cookie(request, "routiqo_admin_session");
        var value = sessions.renew(credential);
        long maxAge = Math.max(1, Duration.between(Instant.now(), value.expiresAt()).toSeconds());
        response.addHeader(HttpHeaders.SET_COOKIE, policy.cookie("routiqo_admin_session", credential,
                (int) Math.min(900, maxAge)));
        return new SessionResponse(value.accountId(), value.expiresAt(), value.absoluteExpiresAt());
    }
    @PostMapping("/logout") ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        AdminHttp.emptyBody(request);
        String credential = cookie(request, "routiqo_admin_session");
        if (credential != null) try { sessions.revoke(credential); } catch (SecurityException malformed) { /* Clear malformed cookie. */ }
        response.addHeader(HttpHeaders.SET_COOKIE, policy.cookie("routiqo_admin_session", "", 0));
        response.addHeader(HttpHeaders.SET_COOKIE, policy.cookie("routiqo_admin_binding", "", 0));
        return ResponseEntity.noContent().build();
    }
    private String cookie(HttpServletRequest request, String name) { return BrowserCookies.read(request, policy, name); }
    @ExceptionHandler(SecurityException.class) ResponseEntity<Void> denied() { return ResponseEntity.status(401).build(); }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<Void> bad() { return ResponseEntity.badRequest().build(); }
    @ExceptionHandler(DataAccessException.class) ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
}
