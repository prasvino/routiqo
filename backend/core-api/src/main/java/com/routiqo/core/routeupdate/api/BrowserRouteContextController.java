package com.routiqo.core.routeupdate.api;

import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.journey.application.JourneyNotFound;
import com.routiqo.core.routeupdate.application.LiveRouteContextConflict;
import com.routiqo.core.routeupdate.application.LiveRouteContextReader;
import com.routiqo.core.routeupdate.application.RouteBindingConflict;
import com.routiqo.core.routeupdate.application.RouteBindingRateLimited;
import com.routiqo.core.routeupdate.application.RouteBindingService;
import com.routiqo.core.routeupdate.application.RouteBindingUnavailable;
import com.routiqo.core.routeupdate.domain.RouteBindingOutcome;
import com.routiqo.core.routeupdate.domain.StoredLiveRouteContext;
import com.routiqo.core.security.BrowserAuthPolicy;
import com.routiqo.core.security.BrowserCookies;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/journeys/{id}/route-context")
@Profile("web-auth & routing & persistence")
@ConditionalOnProperty(name = "ROUTIQO_LIVE_ROUTE_BINDING_API_ENABLED", havingValue = "true")
public final class BrowserRouteContextController {
    private static final UUID NIL_ID = new UUID(0, 0);
    private final LiveRouteContextReader contexts;
    private final RouteBindingService bindings;
    private final GoogleSessionService sessions;
    private final BrowserAuthPolicy policy;
    private final AuthRateGate rates;

    public BrowserRouteContextController(LiveRouteContextReader contexts,
            RouteBindingService bindings, GoogleSessionService sessions,
            BrowserAuthPolicy policy, AuthRateGate rates) {
        this.contexts = contexts;
        this.bindings = bindings;
        this.sessions = sessions;
        this.policy = policy;
        this.rates = rates;
    }

    public record Context(UUID contextId, String revision, List<UUID> anchorIds,
            Instant issuedAt, Instant expiresAt) {
        static Context from(StoredLiveRouteContext stored) {
            List<UUID> anchors = stored.context().anchorIds().stream()
                    .sorted(Comparator.comparing(UUID::toString)).toList();
            return new Context(stored.context().contextId(),
                    Long.toString(stored.context().revision()), anchors,
                    stored.issuedAt(), stored.expiresAt());
        }

        @Override public String toString() { return "BrowserRouteContext[private]"; }
    }

    public record ReadResponse(Context context) {
        @Override public String toString() { return "BrowserRouteContextRead[private]"; }
    }

    public record BindingResponse(String status, Context context) {
        static BindingResponse from(RouteBindingOutcome outcome) {
            return new BindingResponse(outcome.status().name().toLowerCase(Locale.ROOT),
                    outcome.context().map(Context::from).orElse(null));
        }

        @Override public String toString() { return "BrowserRouteContextBinding[private]"; }
    }

    @GetMapping ReadResponse read(@PathVariable String id, HttpServletRequest request) {
        noQuery(request);
        UUID actor = actor(request);
        allowRead(actor);
        return new ReadResponse(contexts.read(actor, id(id)).map(Context::from).orElse(null));
    }

    @PostMapping BindingResponse bind(@PathVariable String id, HttpServletRequest request) {
        noQuery(request);
        UUID actor = actor(request);
        BrowserRouteContextJson.BindingRequest input = BrowserRouteContextJson.binding(request);
        return BindingResponse.from(bindings.bind(actor, id(id), input.route(),
                input.alternativeIndex(), input.expectedContextId()));
    }

    private UUID actor(HttpServletRequest request) {
        UUID actor;
        try {
            actor = sessions.authenticate(BrowserCookies.read(request, policy, "routiqo_session"));
        } catch (DataAccessException | TransactionException unavailable) {
            throw new RouteContextUnavailable();
        }
        var expected = request.getHeaders("X-Routiqo-Account");
        if (!expected.hasMoreElements() || !actor.toString().equals(expected.nextElement())
                || expected.hasMoreElements()) throw new SecurityException("Account context changed");
        return actor;
    }

    private void allowRead(UUID actor) {
        try {
            if (!rates.allow(actor.toString(), "route-context-read-account", 60)) {
                throw new RouteBindingRateLimited();
            }
        } catch (RouteBindingRateLimited limited) {
            throw limited;
        } catch (RuntimeException unavailable) {
            throw new RouteContextUnavailable();
        }
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new IllegalArgumentException(
                "Invalid route context request");
    }

    private static UUID id(String value) {
        if (value == null || !value.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Invalid journey identifier");
        }
        UUID id = UUID.fromString(value);
        if (NIL_ID.equals(id)) throw new IllegalArgumentException("Invalid journey identifier");
        return id;
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Void> unauthenticated() { return ResponseEntity.status(401).build(); }

    @ExceptionHandler(JourneyNotFound.class)
    ResponseEntity<Void> missing() { return ResponseEntity.notFound().build(); }

    @ExceptionHandler({RouteBindingConflict.class, LiveRouteContextConflict.class})
    ResponseEntity<Void> conflict() { return ResponseEntity.status(409).build(); }

    @ExceptionHandler(RouteBindingRateLimited.class)
    ResponseEntity<Void> limited() {
        return ResponseEntity.status(429).header("Retry-After", "60").build();
    }

    @ExceptionHandler({AccountWriteUnavailable.class, RouteBindingUnavailable.class,
            RouteContextUnavailable.class})
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }

    @ExceptionHandler({IllegalArgumentException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Void> invalid() { return ResponseEntity.badRequest().build(); }

    private static final class RouteContextUnavailable extends RuntimeException {}
}
