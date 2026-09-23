package com.routiqo.core.publiclive.api;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.publiclive.infrastructure.JdbcCommunityTrafficV3;
import com.routiqo.core.security.BrowserAuthPolicy;
import com.routiqo.core.security.BrowserCookies;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Journey-scoped canonical feed; no client-selected anchor or coordinates. */
@RestController
@RequestMapping("/api/v1/journeys/{id}/community-traffic")
@Profile("web-auth & routing & persistence")
@ConditionalOnProperty(name = "ROUTIQO_COMMUNITY_TRAFFIC_V3_ENABLED", havingValue = "true")
public final class BrowserCommunityTrafficV3Controller {
    private final JdbcCommunityTrafficV3 store;
    private final GoogleSessionService sessions;
    private final BrowserAuthPolicy policy;
    private final AuthRateGate rates;

    public BrowserCommunityTrafficV3Controller(JdbcCommunityTrafficV3 store,
            GoogleSessionService sessions, BrowserAuthPolicy policy, AuthRateGate rates) {
        this.store = store;
        this.sessions = sessions;
        this.policy = policy;
        this.rates = rates;
    }

    public record ReportResponse(String status) {}

    @GetMapping ResponseEntity<JdbcCommunityTrafficV3.Feed> read(@PathVariable String id,
            HttpServletRequest request) {
        noQuery(request);
        UUID actor = actor(request);
        UUID journey = id(id);
        allow(actor, "community-traffic-v3-read", 12);
        var feed = store.read(actor, journey);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(feed);
    }

    @PostMapping("/{ref}/reports") ResponseEntity<ReportResponse> report(@PathVariable String id,
            @PathVariable String ref, HttpServletRequest request) {
        noQuery(request);
        UUID actor = actor(request);
        UUID journey = id(id);
        UUID reference = id(ref);
        var body = BrowserCommunityTrafficV3Json.parse(request);
        allow(actor, "community-traffic-v3-report", 5);
        store.report(actor, journey, reference, body.requestId(), body.reason());
        return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ReportResponse("received"));
    }

    private UUID actor(HttpServletRequest request) {
        UUID actor = sessions.authenticate(BrowserCookies.read(request, policy, "routiqo_session"));
        var account = request.getHeaders("X-Routiqo-Account");
        if (!account.hasMoreElements() || !actor.toString().equals(account.nextElement())
                || account.hasMoreElements()) throw new SecurityException("Account context changed");
        return actor;
    }

    private void allow(UUID actor, String action, int limit) {
        try {
            if (!rates.allow(actor.toString(), action, limit)) throw new Limited();
        } catch (Limited limited) {
            throw limited;
        } catch (RuntimeException unavailable) {
            throw new Unavailable();
        }
    }

    private static UUID id(String raw) {
        return BrowserCommunityTrafficV3Json.id(raw);
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new IllegalArgumentException("Invalid query");
    }

    @ExceptionHandler(SecurityException.class) ResponseEntity<Void> unauthorized() {
        return ResponseEntity.status(401).build();
    }
    @ExceptionHandler(JdbcCommunityTrafficV3.Missing.class) ResponseEntity<Void> missing() {
        return ResponseEntity.notFound().build();
    }
    @ExceptionHandler(JdbcCommunityTrafficV3.Conflict.class) ResponseEntity<Void> conflict() {
        return ResponseEntity.status(409).build();
    }
    @ExceptionHandler(Limited.class) ResponseEntity<Void> limited() {
        return ResponseEntity.status(429).header("Retry-After", "60").build();
    }
    @ExceptionHandler({IllegalArgumentException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<Void> invalid() { return ResponseEntity.badRequest().build(); }
    @ExceptionHandler({DataAccessException.class, Unavailable.class}) ResponseEntity<Void> unavailable() {
        return ResponseEntity.status(503).build();
    }
    private static final class Limited extends RuntimeException {}
    private static final class Unavailable extends RuntimeException {}
}
