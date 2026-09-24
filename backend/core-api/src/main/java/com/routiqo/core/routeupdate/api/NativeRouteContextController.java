package com.routiqo.core.routeupdate.api;

import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.journey.application.JourneyNotFound;
import com.routiqo.core.routeupdate.application.LiveRouteContextConflict;
import com.routiqo.core.routeupdate.application.LiveRouteContextReader;
import com.routiqo.core.routeupdate.application.RouteBindingConflict;
import com.routiqo.core.routeupdate.application.RouteBindingRateLimited;
import com.routiqo.core.routeupdate.application.RouteBindingService;
import com.routiqo.core.routeupdate.application.RouteBindingUnavailable;
import com.routiqo.core.routeupdate.domain.RouteBindingOutcome;
import com.routiqo.core.routeupdate.domain.StoredLiveRouteContext;
import com.routiqo.core.security.NativeAuthGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

/** Owner-only recovery and explicit private route preparation. */
@RestController
@RequestMapping("/api/v1/native/journeys/{id}/route-context")
@Profile("native-auth & routing & persistence")
@ConditionalOnProperty(name = "ROUTIQO_NATIVE_LIVE_ROUTE_BINDING_API_ENABLED", havingValue = "true")
public final class NativeRouteContextController {
    private static final UUID NIL_ID = new UUID(0, 0);
    private final LiveRouteContextReader contexts;
    private final RouteBindingService bindings;
    private final AuthRateGate rates;

    public NativeRouteContextController(LiveRouteContextReader contexts, RouteBindingService bindings,
            AuthRateGate rates) {
        this.contexts = contexts; this.bindings = bindings; this.rates = rates;
    }

    public record Context(UUID contextId, String revision, List<UUID> anchorIds,
            Instant issuedAt, Instant expiresAt) {
        static Context from(StoredLiveRouteContext stored) {
            return new Context(stored.context().contextId(), Long.toString(stored.context().revision()),
                    stored.context().anchorIds().stream().sorted(Comparator.comparing(UUID::toString)).toList(),
                    stored.issuedAt(), stored.expiresAt());
        }
        @Override public String toString() { return "NativeRouteContext[private]"; }
    }
    public record ReadResponse(Context context) {
        @Override public String toString() { return "NativeRouteContextRead[private]"; }
    }
    public record BindingResponse(String status, Context context) {
        static BindingResponse from(RouteBindingOutcome outcome) {
            return new BindingResponse(outcome.status().name().toLowerCase(Locale.ROOT),
                    outcome.context().map(Context::from).orElse(null));
        }
        @Override public String toString() { return "NativeRouteContextBinding[private]"; }
    }

    @GetMapping ReadResponse read(@PathVariable String id, HttpServletRequest request) {
        UUID actor = actor(request);
        UUID journey = id(id);
        try {
            if (!rates.allow(actor.toString(), "route-context-read-account", 60))
                throw new RouteBindingRateLimited();
        } catch (RouteBindingRateLimited limited) {
            throw limited;
        } catch (RuntimeException unavailable) {
            throw new RouteContextUnavailable();
        }
        return new ReadResponse(contexts.read(actor, journey).map(Context::from).orElse(null));
    }

    @PostMapping BindingResponse bind(@PathVariable String id, HttpServletRequest request) {
        UUID actor = actor(request);
        UUID journey = id(id);
        var input = NativeRouteContextJson.binding(request);
        return BindingResponse.from(bindings.bind(actor, journey, input.route(),
                input.alternativeIndex(), input.expectedContextId()));
    }

    private static UUID actor(HttpServletRequest request) {
        Object value = request.getAttribute(NativeAuthGuard.ACCOUNT_ATTRIBUTE);
        if (!(value instanceof String account)) throw new SecurityException("Native account missing");
        var expected = request.getHeaders("X-Routiqo-Account");
        if (!expected.hasMoreElements() || !account.equals(expected.nextElement()) || expected.hasMoreElements())
            throw new SecurityException("Account context changed");
        return UUID.fromString(account);
    }

    private static UUID id(String value) {
        if (value == null || !value.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Invalid journey identifier");
        UUID id = UUID.fromString(value);
        if (NIL_ID.equals(id)) throw new IllegalArgumentException("Invalid journey identifier");
        return id;
    }

    @ExceptionHandler(SecurityException.class) ResponseEntity<Void> unauthenticated() {
        return ResponseEntity.status(401).build();
    }
    @ExceptionHandler(JourneyNotFound.class) ResponseEntity<Void> missing() {
        return ResponseEntity.notFound().build();
    }
    @ExceptionHandler({RouteBindingConflict.class, LiveRouteContextConflict.class})
    ResponseEntity<Void> conflict() { return ResponseEntity.status(409).build(); }
    @ExceptionHandler(RouteBindingRateLimited.class) ResponseEntity<Void> limited() {
        return ResponseEntity.status(429).header("Retry-After", "60").build();
    }
    @ExceptionHandler({AccountWriteUnavailable.class, RouteBindingUnavailable.class,
            RouteContextUnavailable.class, DataAccessException.class, TransactionException.class})
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Void> invalid() {
        return ResponseEntity.badRequest().build();
    }
    private static final class RouteContextUnavailable extends RuntimeException {}
}
