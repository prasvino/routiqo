package com.routiqo.core.journey.api;

import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.journey.application.*;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.security.BrowserAuthPolicy;
import com.routiqo.core.security.BrowserCookies;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/journeys")
@Profile("web-auth")
public final class BrowserJourneyController {
    private final JourneyService journeys;
    private final GoogleSessionService sessions;
    private final BrowserAuthPolicy policy;
    public BrowserJourneyController(JourneyService journeys, GoogleSessionService sessions, BrowserAuthPolicy policy) {
        this.journeys = journeys; this.sessions = sessions; this.policy = policy;
    }
    public record StartRequest(String id, String kind) {}
    public record JourneyResponse(UUID id, String kind, String status, Instant startedAt, Instant completedAt) {
        static JourneyResponse from(Journey journey) {
            return new JourneyResponse(journey.id(), journey.kind().name().toLowerCase(Locale.ROOT),
                    journey.status().name().toLowerCase(Locale.ROOT), journey.startedAt(), journey.completedAt());
        }
    }
    public record PageResponse(List<JourneyResponse> journeys, JourneyStore.Cursor next) {}
    @PostMapping JourneyResponse start(@RequestBody StartRequest input, HttpServletRequest request) {
        UUID actor = actor(request);
        Journey.Kind kind = switch (input.kind() == null ? "" : input.kind()) {
            case "trip" -> Journey.Kind.TRIP;
            case "commute" -> Journey.Kind.COMMUTE;
            default -> throw new IllegalArgumentException("Invalid journey kind");
        };
        return JourneyResponse.from(journeys.start(actor, id(input.id()), kind));
    }
    @GetMapping("/{id}") JourneyResponse get(@PathVariable String id, HttpServletRequest request) {
        return JourneyResponse.from(journeys.get(actor(request), id(id)));
    }
    @PostMapping("/{id}/complete") JourneyResponse complete(@PathVariable String id, HttpServletRequest request) {
        return JourneyResponse.from(journeys.complete(actor(request), id(id)));
    }
    @GetMapping PageResponse list(@RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String beforeStartedAt, @RequestParam(required = false) String beforeId,
            HttpServletRequest request) {
        UUID actor = actor(request);
        JourneyStore.Cursor cursor = null;
        if ((beforeStartedAt == null) != (beforeId == null)) throw new IllegalArgumentException("Incomplete cursor");
        if (beforeStartedAt != null) {
            if (beforeStartedAt.length() > 40) throw new IllegalArgumentException("Invalid cursor");
            Instant time;
            try { time = Instant.parse(beforeStartedAt); }
            catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("Invalid cursor"); }
            if (time.getNano() % 1000 != 0 || time.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                    || time.isAfter(Instant.parse("9999-12-31T23:59:59Z"))) throw new IllegalArgumentException("Invalid cursor");
            cursor = new JourneyStore.Cursor(time, id(beforeId));
        }
        var page = journeys.list(actor, cursor, limit);
        return new PageResponse(page.journeys().stream().map(JourneyResponse::from).toList(), page.next());
    }
    private UUID actor(HttpServletRequest request) {
        UUID actor = sessions.authenticate(BrowserCookies.read(request, policy, "routiqo_session"));
        var expected = request.getHeaders("X-Routiqo-Account");
        if (!expected.hasMoreElements() || !actor.toString().equals(expected.nextElement()) || expected.hasMoreElements())
            throw new SecurityException("Account context changed");
        return actor;
    }
    private static UUID id(String value) {
        if (value == null || !value.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
            throw new IllegalArgumentException("Invalid journey identifier");
        return UUID.fromString(value);
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<Void> unauthenticated() { return ResponseEntity.status(401).build(); }
    @ExceptionHandler(JourneyNotFound.class) ResponseEntity<Void> missing() { return ResponseEntity.notFound().build(); }
    @ExceptionHandler(JourneyConflict.class) ResponseEntity<Void> conflict() { return ResponseEntity.status(409).build(); }
    @ExceptionHandler(AccountWriteUnavailable.class) ResponseEntity<Void> unavailable() {
        return ResponseEntity.status(503).build();
    }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Void> invalid() { return ResponseEntity.badRequest().build(); }
}
