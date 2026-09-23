package com.routiqo.core.routeupdate.api;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.journey.application.JourneyNotFound;
import com.routiqo.core.journey.application.JourneyService;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.routeupdate.infrastructure.NdmaCapAlerts;
import com.routiqo.core.security.BrowserAuthPolicy;
import com.routiqo.core.security.BrowserCookies;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Official district alerts only; no private signals or route data reach the provider. */
@RestController
@RequestMapping("/api/v1/journeys/{id}/provider-alerts")
@Profile("web-auth & persistence")
@ConditionalOnProperty(name = "ROUTIQO_PROVIDER_LIVE_ENABLED", havingValue = "true")
public final class BrowserProviderLiveController {
    private static final Pattern JOURNEY_ID = Pattern.compile(
            "^(?!00000000-0000-0000-0000-000000000000$)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private final JourneyService journeys;
    private final GoogleSessionService sessions;
    private final BrowserAuthPolicy policy;
    private final AuthRateGate rates;
    private final NdmaCapAlerts alerts;

    public BrowserProviderLiveController(JourneyService journeys,
            GoogleSessionService sessions, BrowserAuthPolicy policy,
            AuthRateGate rates, NdmaCapAlerts alerts) {
        this.journeys = journeys;
        this.sessions = sessions;
        this.policy = policy;
        this.rates = rates;
        this.alerts = alerts;
    }

    public record AlertsResponse(String region, String scope, String source,
            List<NdmaCapAlerts.Alert> alerts) {}

    @GetMapping
    ResponseEntity<AlertsResponse> read(@PathVariable String id, HttpServletRequest request) {
        if (request.getQueryString() != null || !JOURNEY_ID.matcher(id).matches())
            throw new IllegalArgumentException("Invalid journey request");
        UUID actor = sessions.authenticate(BrowserCookies.read(request, policy, "routiqo_session"));
        var headers = request.getHeaders("X-Routiqo-Account");
        if (!headers.hasMoreElements() || !actor.toString().equals(headers.nextElement())
                || headers.hasMoreElements()) throw new SecurityException("Account context changed");
        UUID journeyId = UUID.fromString(id);
        active(actor, journeyId);
        if (!rates.allow(actor.toString(), "provider-live-read-account", 5))
            return ResponseEntity.status(429).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
        List<NdmaCapAlerts.Alert> current = alerts.current();
        active(actor, journeyId);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AlertsResponse("Chennai district area", "District-wide alerts; not road conditions",
                        "NDMA SACHET", current));
    }

    private void active(UUID actor, UUID journeyId) {
        if (journeys.get(actor, journeyId).status() != Journey.Status.ACTIVE)
            throw new JourneyNotFound();
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Void> unauthorized() { return ResponseEntity.status(401).build(); }

    @ExceptionHandler(JourneyNotFound.class)
    ResponseEntity<Void> missing() { return ResponseEntity.notFound().build(); }

    @ExceptionHandler({IllegalArgumentException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Void> invalid() { return ResponseEntity.badRequest().build(); }

    @ExceptionHandler({IllegalStateException.class, DataAccessException.class})
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
}
