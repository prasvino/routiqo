package com.routiqo.core.journal.api;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.journal.application.JournalConflict;
import com.routiqo.core.journal.application.JournalIneligible;
import com.routiqo.core.journal.application.JournalService;
import com.routiqo.core.journal.domain.JournalAnnotation;
import com.routiqo.core.journal.domain.JournalMutation;
import com.routiqo.core.journey.application.JourneyNotFound;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.security.BrowserAuthPolicy;
import com.routiqo.core.security.BrowserCookies;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/journeys/{id}/journal")
@Profile("web-auth")
public final class BrowserJournalController {
    private final JournalService journals;
    private final GoogleSessionService sessions;
    private final BrowserAuthPolicy policy;
    private final AuthRateGate rates;

    public BrowserJournalController(JournalService journals, GoogleSessionService sessions,
            BrowserAuthPolicy policy, AuthRateGate rates) {
        this.journals = journals; this.sessions = sessions; this.policy = policy; this.rates = rates;
    }

    public record SaveRequest(String title, String notes, Long expectedVersion, String mutationId) {
        @Override public String toString() { return "SaveRequest[private]"; }
    }
    public record JourneyResponse(UUID id, String kind, String status, Instant startedAt, Instant completedAt) {
        static JourneyResponse from(Journey journey) {
            return new JourneyResponse(journey.id(), journey.kind().name().toLowerCase(Locale.ROOT),
                    journey.status().name().toLowerCase(Locale.ROOT), journey.startedAt(), journey.completedAt());
        }
    }
    public record AnnotationResponse(String title, String notes, long version, Instant updatedAt) {
        static AnnotationResponse from(JournalAnnotation annotation) {
            return new AnnotationResponse(annotation.title(), annotation.notes(), annotation.version(), annotation.updatedAt());
        }
        @Override public String toString() { return "AnnotationResponse[private]"; }
    }
    public record JournalResponse(JourneyResponse journey, AnnotationResponse annotation) {
        static JournalResponse from(JournalService.JournalView view) {
            return new JournalResponse(JourneyResponse.from(view.journey()), AnnotationResponse.from(view.annotation()));
        }
    }

    @GetMapping JournalResponse get(@PathVariable String id, HttpServletRequest request) {
        return JournalResponse.from(journals.get(actor(request), id(id)));
    }

    @PostMapping JournalResponse save(@PathVariable String id, @RequestBody SaveRequest input,
            HttpServletRequest request) {
        UUID actor = actor(request);
        if (!rates.allow(actor.toString(), "journal-write-account", 20)) throw new TooManyRequests();
        if (input == null) throw new IllegalArgumentException("Journal input is required");
        if (input.expectedVersion() == null) throw new IllegalArgumentException("Expected version is required");
        var mutation = new JournalMutation(input.title(), input.notes(), input.expectedVersion(), id(input.mutationId()));
        return JournalResponse.from(journals.save(actor, id(id), mutation));
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
            throw new IllegalArgumentException("Invalid identifier");
        return UUID.fromString(value);
    }

    @ExceptionHandler(SecurityException.class) ResponseEntity<Void> unauthenticated() { return ResponseEntity.status(401).build(); }
    @ExceptionHandler(JourneyNotFound.class) ResponseEntity<Void> missing() { return ResponseEntity.notFound().build(); }
    @ExceptionHandler({JournalConflict.class, JournalIneligible.class}) ResponseEntity<Void> conflict() {
        return ResponseEntity.status(409).build();
    }
    @ExceptionHandler(TooManyRequests.class) ResponseEntity<Void> limited() {
        return ResponseEntity.status(429).header("Retry-After", "60").build();
    }
    @ExceptionHandler({IllegalArgumentException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Void> invalid() { return ResponseEntity.badRequest().build(); }

    private static final class TooManyRequests extends RuntimeException {}
}
