package com.routiqo.core.journal.api;

import com.routiqo.core.journal.application.JournalIneligible;
import com.routiqo.core.journal.application.JournalService;
import com.routiqo.core.journal.domain.JournalAnnotation;
import com.routiqo.core.journey.application.JourneyNotFound;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.security.NativeAuthGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only native boundary for the owner's completed trip journal. */
@RestController
@RequestMapping("/api/v1/native/journeys/{id}/journal")
@Profile("native-auth")
public final class NativeJournalController {
    private final JournalService journals;

    public NativeJournalController(JournalService journals) { this.journals = journals; }

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

    private static UUID actor(HttpServletRequest request) {
        Object value = request.getAttribute(NativeAuthGuard.ACCOUNT_ATTRIBUTE);
        if (!(value instanceof String account)) throw new SecurityException("Native account missing");
        var expected = request.getHeaders("X-Routiqo-Account");
        if (!expected.hasMoreElements() || !account.equals(expected.nextElement()) || expected.hasMoreElements())
            throw new SecurityException("Account context changed");
        return UUID.fromString(account);
    }

    private static UUID id(String value) {
        if (value == null || !value.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("Invalid journal identifier");
        return UUID.fromString(value);
    }

    @ExceptionHandler(SecurityException.class) ResponseEntity<Void> unauthenticated() { return ResponseEntity.status(401).build(); }
    @ExceptionHandler(JourneyNotFound.class) ResponseEntity<Void> missing() { return ResponseEntity.notFound().build(); }
    @ExceptionHandler(JournalIneligible.class) ResponseEntity<Void> ineligible() { return ResponseEntity.status(409).build(); }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Void> invalid() { return ResponseEntity.badRequest().build(); }
}
