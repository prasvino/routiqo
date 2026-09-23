package com.routiqo.core.journey.api;

import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.journey.application.JourneyConflict;
import com.routiqo.core.journey.application.JourneyNotFound;
import com.routiqo.core.journey.application.JourneyService;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.security.NativeAuthGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** The bearer-only native resource boundary; browser cookies never select its actor. */
@RestController
@RequestMapping("/api/v1/native/journeys")
@Profile("native-auth")
public final class NativeJourneyController {
    private final JourneyService journeys;
    public NativeJourneyController(JourneyService journeys) { this.journeys = journeys; }

    public record JourneyResponse(UUID id, String kind, String status, Instant startedAt, Instant completedAt) {
        static JourneyResponse from(Journey journey) {
            return new JourneyResponse(journey.id(), journey.kind().name().toLowerCase(Locale.ROOT),
                    journey.status().name().toLowerCase(Locale.ROOT), journey.startedAt(), journey.completedAt());
        }
    }
    public record PageResponse(List<JourneyResponse> journeys) {}

    @PostMapping JourneyResponse start(HttpServletRequest request) {
        UUID actor = actor(request);
        var input = NativeJourneyJson.object(request, Set.of("id", "kind"));
        Journey.Kind kind = switch (NativeJourneyJson.text(input, "kind", 7)) {
            case "trip" -> Journey.Kind.TRIP;
            case "commute" -> Journey.Kind.COMMUTE;
            default -> throw new IllegalArgumentException("Invalid journey kind");
        };
        return JourneyResponse.from(journeys.start(actor, NativeJourneyJson.uuid(input, "id"), kind));
    }
    @GetMapping("/{id}") JourneyResponse get(@PathVariable String id, HttpServletRequest request) {
        return JourneyResponse.from(journeys.get(actor(request), id(id)));
    }
    @PostMapping("/{id}/complete") JourneyResponse complete(@PathVariable String id, HttpServletRequest request) {
        NativeJourneyJson.object(request, Set.of());
        return JourneyResponse.from(journeys.complete(actor(request), id(id)));
    }
    @GetMapping PageResponse list(HttpServletRequest request) {
        var page = journeys.list(actor(request), null, 50);
        return new PageResponse(page.journeys().stream().map(JourneyResponse::from).toList());
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
        if (value == null || !value.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
            throw new IllegalArgumentException("Invalid journey identifier");
        return UUID.fromString(value);
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<Void> unauthenticated() { return ResponseEntity.status(401).build(); }
    @ExceptionHandler(JourneyNotFound.class) ResponseEntity<Void> missing() { return ResponseEntity.notFound().build(); }
    @ExceptionHandler(JourneyConflict.class) ResponseEntity<Void> conflict() { return ResponseEntity.status(409).build(); }
    @ExceptionHandler(AccountWriteUnavailable.class) ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Void> invalid() { return ResponseEntity.badRequest().build(); }
}
