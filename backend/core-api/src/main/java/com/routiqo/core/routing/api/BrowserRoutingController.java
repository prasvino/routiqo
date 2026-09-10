package com.routiqo.core.routing.api;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.routing.application.RouteProvider;
import com.routiqo.core.routing.application.PlaceProvider;
import com.routiqo.core.routing.domain.PlaceQuery;
import com.routiqo.core.routing.domain.RouteRequest;
import com.routiqo.core.security.BrowserAuthPolicy;
import com.routiqo.core.security.BrowserCookies;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("web-auth & routing")
@RequestMapping("/api/v1/routes")
public final class BrowserRoutingController {
    private final RouteProvider provider;
    private final PlaceProvider places;
    private final GoogleSessionService sessions;
    private final BrowserAuthPolicy policy;
    private final AuthRateGate rates;
    private final Clock clock;
    public BrowserRoutingController(RouteProvider provider, PlaceProvider places, GoogleSessionService sessions,
            BrowserAuthPolicy policy, AuthRateGate rates, Clock clock) {
        this.provider = provider; this.places = places; this.sessions = sessions; this.policy = policy; this.rates = rates; this.clock = clock;
    }
    public record Input(String mode, List<Double> origin, List<Double> destination) {}
    public record Option(double distanceMetres, double durationSeconds, List<List<Double>> geometry) {}
    public record Result(String provider, Instant calculatedAt, List<Option> routes) {}
    private UUID actor(HttpServletRequest request) {
        var actor = sessions.authenticate(BrowserCookies.read(request, policy, "routiqo_session"));
        var expected = request.getHeaders("X-Routiqo-Account");
        if (!expected.hasMoreElements() || !actor.toString().equals(expected.nextElement()) || expected.hasMoreElements())
            throw new SecurityException("Account context changed");
        return actor;
    }
    public record SearchInput(String query) {
        @Override public String toString() { return "SearchInput[private]"; }
    }
    public record Place(String id, String label, List<Double> coordinate) {}
    public record SearchResult(String provider, List<Place> places, String attribution) {}
    @PostMapping("/places") public ResponseEntity<SearchResult> search(@RequestBody SearchInput input, HttpServletRequest request) {
        var actor = actor(request);
        var query = new PlaceQuery(input.query());
        if (!rates.allow(actor.toString(), "place-search-account", 20))
            return ResponseEntity.status(429).header("Retry-After", "60").build();
        var result = places.search(query);
        return ResponseEntity.ok(new SearchResult("mapbox", result.places().stream().map(place ->
                new Place(place.id(), place.label(), List.of(place.coordinate().longitude(), place.coordinate().latitude()))).toList(), result.attribution()));
    }
    @PostMapping public ResponseEntity<Result> routes(@RequestBody Input input, HttpServletRequest request) {
        var actor = actor(request);
        var mode = switch (input.mode() == null ? "" : input.mode()) {
            case "driving" -> RouteRequest.Mode.DRIVING;
            case "walking" -> RouteRequest.Mode.WALKING;
            case "cycling" -> RouteRequest.Mode.CYCLING;
            default -> throw new IllegalArgumentException("Invalid route mode");
        };
        var routeRequest = new RouteRequest(mode, coordinate(input.origin()), coordinate(input.destination()));
        if (!rates.allow(actor.toString(), "routing-account", 20))
            return ResponseEntity.status(429).header("Retry-After", "60").build();
        var routes = provider.routes(routeRequest).stream().map(route -> new Option(route.distanceMetres(),
                route.durationSeconds(), route.geometry().stream().map(point -> List.of(point.longitude(), point.latitude())).toList())).toList();
        return ResponseEntity.ok(new Result("mapbox", clock.instant(), routes));
    }
    private static RouteRequest.Coordinate coordinate(List<Double> values) {
        if (values == null || values.size() != 2 || values.get(0) == null || values.get(1) == null)
            throw new IllegalArgumentException("Invalid route coordinate");
        return new RouteRequest.Coordinate(values.get(0), values.get(1));
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<Void> unauthenticated() { return ResponseEntity.status(401).build(); }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<Void> invalid() { return ResponseEntity.badRequest().build(); }
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
}
