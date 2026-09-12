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
import java.math.BigDecimal;
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
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

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

    @JsonDeserialize(using = SaveRequestDeserializer.class)
    public record SaveRequest(String title, String notes, long expectedVersion, String mutationId) {
        @Override public String toString() { return "SaveRequest[private]"; }
    }

    public static final class SaveRequestDeserializer extends StdDeserializer<SaveRequest> {
        private static final int MAX_NUMBER_TEXT = 64;
        private static final int MAX_EXPONENT = 100;

        public SaveRequestDeserializer() { super(SaveRequest.class); }

        @Override public SaveRequest deserialize(JsonParser parser, DeserializationContext context)
                throws JacksonException {
            if (parser.currentToken() != JsonToken.START_OBJECT) return invalid(context);
            String title = null; String notes = null; String mutationId = null;
            Long expectedVersion = null;
            boolean titleSeen = false; boolean notesSeen = false;
            boolean versionSeen = false; boolean mutationSeen = false;
            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                if (token != JsonToken.PROPERTY_NAME) return invalid(context);
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if (value == null) return invalid(context);
                switch (name) {
                    case "title" -> {
                        if (titleSeen || value != JsonToken.VALUE_STRING) return invalid(context);
                        titleSeen = true; title = parser.getString();
                    }
                    case "notes" -> {
                        if (notesSeen || value != JsonToken.VALUE_STRING) return invalid(context);
                        notesSeen = true; notes = parser.getString();
                    }
                    case "expectedVersion" -> {
                        if (versionSeen || !value.isNumeric()) return invalid(context);
                        versionSeen = true; expectedVersion = version(parser, context);
                    }
                    case "mutationId" -> {
                        if (mutationSeen || value != JsonToken.VALUE_STRING) return invalid(context);
                        mutationSeen = true; mutationId = parser.getString();
                    }
                    default -> parser.skipChildren();
                }
            }
            if (!titleSeen || !notesSeen || !versionSeen || !mutationSeen) return invalid(context);
            return new SaveRequest(title, notes, expectedVersion, mutationId);
        }

        private static long version(JsonParser parser, DeserializationContext context) throws JacksonException {
            String raw = parser.getString();
            if (raw.length() > MAX_NUMBER_TEXT || exponentMagnitude(raw) > MAX_EXPONENT) return invalid(context);
            final BigDecimal number;
            try { number = new BigDecimal(raw); }
            catch (NumberFormatException invalid) { return invalid(context); }
            if (number.stripTrailingZeros().scale() > 0
                    || number.compareTo(BigDecimal.ZERO) < 0
                    || number.compareTo(BigDecimal.valueOf(JournalAnnotation.MAX_EXPECTED_VERSION)) > 0)
                return invalid(context);
            try { return number.longValueExact(); }
            catch (ArithmeticException invalid) { return invalid(context); }
        }

        private static int exponentMagnitude(String raw) {
            int marker = Math.max(raw.indexOf('e'), raw.indexOf('E'));
            if (marker < 0) return 0;
            String exponent = raw.substring(marker + 1);
            if (exponent.startsWith("+") || exponent.startsWith("-")) exponent = exponent.substring(1);
            if (exponent.length() > 3) return MAX_EXPONENT + 1;
            try { return Integer.parseInt(exponent); }
            catch (NumberFormatException invalid) { return MAX_EXPONENT + 1; }
        }

        private static <T> T invalid(DeserializationContext context) throws JacksonException {
            return context.reportInputMismatch(SaveRequest.class, "Invalid journal request");
        }
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
