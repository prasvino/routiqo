package com.routiqo.core.routing.api;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.routing.application.PlaceProvider;
import com.routiqo.core.routing.application.RouteProvider;
import com.routiqo.core.routing.domain.RouteOutsideCoverageException;
import com.routiqo.core.security.NativeAuthGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit owner-only route planning; no journey or presence mutation. */
@RestController
@Profile("native-auth & routing")
@RequestMapping("/api/v1/native/routes")
@ConditionalOnProperty(name = "ROUTIQO_NATIVE_ROUTING_API_ENABLED", havingValue = "true")
public final class NativeRoutingController {
    private final RouteProvider routes;
    private final PlaceProvider places;
    private final AuthRateGate rates;
    private final Clock clock;

    public NativeRoutingController(RouteProvider routes, PlaceProvider places, AuthRateGate rates, Clock clock) {
        this.routes = routes; this.places = places; this.rates = rates; this.clock = clock;
    }

    public record Place(String id, String label, List<Double> coordinate) {
        @Override public String toString() { return "Place[private]"; }
    }
    public record SearchResult(String provider, List<Place> places, String attribution) {
        @Override public String toString() { return "SearchResult[private]"; }
    }
    public record Step(String instruction, double distanceMetres, double durationSeconds, List<Double> location) {
        @Override public String toString() { return "Step[private]"; }
    }
    public record Option(double distanceMetres, double durationSeconds, List<List<Double>> geometry, List<Step> steps) {
        @Override public String toString() { return "Option[private]"; }
    }
    public record Result(String provider, Instant calculatedAt, List<Option> routes) {
        @Override public String toString() { return "Result[private]"; }
    }

    @PostMapping("/places") ResponseEntity<SearchResult> search(HttpServletRequest request) {
        UUID actor = actor(request);
        var query = NativeRoutingJson.place(request);
        allow(actor, "place-search-account");
        var result = places.search(query);
        var matches = result.places().stream().map(place -> new Place(place.id(), place.label(),
                List.of(place.coordinate().longitude(), place.coordinate().latitude()))).toList();
        return ResponseEntity.ok(new SearchResult(places.identity().wireValue(), matches, result.attribution()));
    }

    @PostMapping ResponseEntity<Result> calculate(HttpServletRequest request) {
        UUID actor = actor(request);
        var input = NativeRoutingJson.route(request);
        allow(actor, "routing-account");
        var options = routes.routes(input).stream().map(route -> new Option(route.distanceMetres(),
                route.durationSeconds(), route.geometry().stream()
                        .map(point -> List.of(point.longitude(), point.latitude())).toList(),
                route.steps().stream().map(step -> new Step(step.instruction(), step.distanceMetres(),
                        step.durationSeconds(), List.of(step.location().longitude(), step.location().latitude())))
                        .toList())).toList();
        return ResponseEntity.ok(new Result(routes.identity().wireValue(), clock.instant(), options));
    }

    private static UUID actor(HttpServletRequest request) {
        Object value = request.getAttribute(NativeAuthGuard.ACCOUNT_ATTRIBUTE);
        if (!(value instanceof String account)) throw new SecurityException("Native account missing");
        var expected = request.getHeaders("X-Routiqo-Account");
        if (!expected.hasMoreElements() || !account.equals(expected.nextElement()) || expected.hasMoreElements())
            throw new SecurityException("Account context changed");
        return UUID.fromString(account);
    }

    private void allow(UUID actor, String category) {
        try {
            if (!rates.allow(actor.toString(), category, 20)) throw new TooManyRequests();
        } catch (TooManyRequests limited) {
            throw limited;
        } catch (RuntimeException unavailable) {
            throw new RoutingUnavailable();
        }
    }

    @ExceptionHandler(SecurityException.class) ResponseEntity<Void> unauthenticated() { return ResponseEntity.status(401).build(); }
    @ExceptionHandler(RouteOutsideCoverageException.class) ResponseEntity<Void> outsideCoverage() {
        return ResponseEntity.status(422).build();
    }
    @ExceptionHandler(TooManyRequests.class) ResponseEntity<Void> limited() {
        return ResponseEntity.status(429).header("Retry-After", "60").build();
    }
    @ExceptionHandler({IllegalStateException.class, RoutingUnavailable.class,
            DataAccessException.class, TransactionException.class})
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Void> invalid() {
        return ResponseEntity.badRequest().build();
    }

    private static final class TooManyRequests extends RuntimeException {}
    private static final class RoutingUnavailable extends RuntimeException {}
}
