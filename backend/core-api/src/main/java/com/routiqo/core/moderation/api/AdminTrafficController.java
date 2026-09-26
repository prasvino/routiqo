package com.routiqo.core.moderation.api;

import com.routiqo.core.security.ConditionalOnExactlyTrue;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.moderation.infrastructure.AdminSessionService;
import com.routiqo.core.moderation.infrastructure.JdbcTrafficReview;
import com.routiqo.core.security.BrowserAuthPolicy;
import com.routiqo.core.security.BrowserCookies;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@Profile("web-auth & persistence & google-auth")
@ConditionalOnExactlyTrue({"ROUTIQO_ADMIN_ENABLED", "ROUTIQO_V3_ADMIN_ENABLED"})
public final class AdminTrafficController {
    private final AdminSessionService sessions;
    private final JdbcTrafficReview review;
    private final BrowserAuthPolicy policy;
    private final AuthRateGate rates;
    public AdminTrafficController(AdminSessionService sessions, JdbcTrafficReview review,
            AdminAccessConfiguration.AdminSettings settings, AuthRateGate rates) {
        this.sessions = sessions; this.review = review; this.policy = settings.policy(); this.rates = rates;
    }
    public record DecisionResponse(String status) {}

    @GetMapping("/community-traffic/reports") JdbcTrafficReview.Queue queue(HttpServletRequest request,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        UUID operator = operator(request);
        if (request.getParameterMap().keySet().stream().anyMatch(key -> !key.equals("cursor") && !key.equals("limit"))
                || request.getParameterValues("cursor") != null && request.getParameterValues("cursor").length != 1
                || request.getParameterValues("limit") != null && request.getParameterValues("limit").length != 1)
            throw new IllegalArgumentException("Invalid query");
        allow(operator, "admin-traffic-read", 30);
        String credential = cookie(request, "routiqo_admin_session");
        return review.queue(operator, cursor, limit, () -> sessions.assertCurrent(operator, credential));
    }
    @PostMapping("/community-traffic/reports/{ref}/dismiss") DecisionResponse dismiss(@PathVariable String ref,
            HttpServletRequest request) { return decide(ref, "DISMISS", request); }
    @PostMapping("/community-traffic/reports/{ref}/suppress") DecisionResponse suppress(@PathVariable String ref,
            HttpServletRequest request) { return decide(ref, "SUPPRESS", request); }
    private DecisionResponse decide(String ref, String action, HttpServletRequest request) {
        noQuery(request);
        UUID operator = operator(request);
        var input = AdminTrafficJson.decision(request);
        allow(operator, "admin-traffic-write", 10);
        String credential = cookie(request, "routiqo_admin_session");
        review.decide(operator, input.requestId(), AdminTrafficJson.id(ref), action, input.reason(),
                () -> sessions.assertCurrent(operator, credential));
        return new DecisionResponse(action.equals("DISMISS") ? "dismissed" : "suppressed");
    }
    private UUID operator(HttpServletRequest request) {
        return sessions.authenticate(cookie(request, "routiqo_admin_session")).accountId();
    }
    private String cookie(HttpServletRequest request, String name) { return BrowserCookies.read(request, policy, name); }
    private void allow(UUID operator, String category, int limit) {
        try { if (!rates.allow(operator.toString(), category, limit)) throw new Limited(); }
        catch (Limited limited) { throw limited; }
        catch (RuntimeException unavailable) { throw new Unavailable(); }
    }
    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new IllegalArgumentException("Invalid query");
    }
    private static void emptyBody(HttpServletRequest request) {
        try {
            byte[] body = request.getInputStream().readNBytes(3);
            if (!(body.length == 0 || new String(body, java.nio.charset.StandardCharsets.US_ASCII).equals("{}")))
                throw new IllegalArgumentException("Invalid request");
        } catch (java.io.IOException error) { throw new IllegalArgumentException("Invalid request"); }
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<Void> denied() { return ResponseEntity.status(401).build(); }
    @ExceptionHandler(JdbcTrafficReview.Missing.class) ResponseEntity<Void> missing() { return ResponseEntity.notFound().build(); }
    @ExceptionHandler(JdbcTrafficReview.Conflict.class) ResponseEntity<Void> conflict() { return ResponseEntity.status(409).build(); }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<Void> bad() { return ResponseEntity.badRequest().build(); }
    @ExceptionHandler(Limited.class) ResponseEntity<Void> limited() { return ResponseEntity.status(429).header("Retry-After", "60").build(); }
    @ExceptionHandler({DataAccessException.class, Unavailable.class}) ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
    private static final class Limited extends RuntimeException {}
    private static final class Unavailable extends RuntimeException {}
}
