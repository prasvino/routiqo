package com.routiqo.core.routeupdate.api;

import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.journey.application.JourneyNotFound;
import com.routiqo.core.routeupdate.application.CatalogSignalService;
import com.routiqo.core.routeupdate.application.PrivateAnchorChoiceService;
import com.routiqo.core.routeupdate.application.PrivateAnchorChoicesUnavailable;
import com.routiqo.core.routeupdate.application.SignalStorageConflict;
import com.routiqo.core.routeupdate.application.SignalStorageDenied;
import com.routiqo.core.routeupdate.application.SignalStorageRateLimited;
import com.routiqo.core.routeupdate.domain.PrivateAnchorChoice;
import com.routiqo.core.routeupdate.domain.PrivateAnchorChoiceSnapshot;
import com.routiqo.core.security.BrowserAuthPolicy;
import com.routiqo.core.security.BrowserCookies;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
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

/** Guarded owner-only choices and exact-context issuance; no public route metadata. */
@RestController
@RequestMapping("/api/v1/journeys/{id}")
@Profile("web-auth & routing & persistence")
@ConditionalOnProperty(name = {"ROUTIQO_LIVE_SIGNAL_API_ENABLED",
        "ROUTIQO_LIVE_CHOICE_API_ENABLED"}, havingValue = "true")
public final class BrowserSignalChoiceController {
    private static final UUID NIL_ID = new UUID(0, 0);
    private final PrivateAnchorChoiceService choices;
    private final CatalogSignalService signals;
    private final GoogleSessionService sessions;
    private final BrowserAuthPolicy policy;
    private final AuthRateGate rates;

    public BrowserSignalChoiceController(PrivateAnchorChoiceService choices,
            CatalogSignalService signals, GoogleSessionService sessions,
            BrowserAuthPolicy policy, AuthRateGate rates) {
        this.choices = choices;
        this.signals = signals;
        this.sessions = sessions;
        this.policy = policy;
        this.rates = rates;
    }

    public record Choice(UUID anchorId, String displayLabel, List<String> categories) {
        public Choice { categories = List.copyOf(categories); }
        static Choice from(PrivateAnchorChoice choice) {
            return new Choice(choice.anchorId(), choice.displayLabel(),
                    choice.categories().stream().map(value -> value.name().toLowerCase(Locale.ROOT))
                            .sorted().toList());
        }
        @Override public String toString() { return "BrowserSignalChoice[private]"; }
    }

    public record ChoiceResponse(UUID contextId, String routeRevision,
            String consentGeneration, Instant issuedAt, Instant expiresAt, List<Choice> choices) {
        public ChoiceResponse { choices = List.copyOf(choices); }
        static ChoiceResponse from(PrivateAnchorChoiceSnapshot snapshot) {
            return new ChoiceResponse(snapshot.contextId(), Long.toString(snapshot.revision()),
                    Long.toString(snapshot.consentGeneration()), snapshot.issuedAt(),
                    snapshot.expiresAt(), snapshot.choices().stream().map(Choice::from).toList());
        }
        @Override public String toString() { return "BrowserSignalChoices[private]"; }
    }

    @GetMapping("/signal-choices") ChoiceResponse read(@PathVariable String id,
            HttpServletRequest request) {
        noQuery(request);
        UUID actor = actor(request);
        UUID journey = id(id);
        allow(actor, "signal-choice-read-request", 30);
        return ChoiceResponse.from(choices.read(actor, journey));
    }

    @PostMapping("/signal-commands/expected-context") BrowserSignalController.GrantResponse issue(
            @PathVariable String id, HttpServletRequest request) {
        noQuery(request);
        UUID actor = actor(request);
        UUID journey = id(id);
        BrowserSignalJson.ExpectedIssue input = BrowserSignalJson.expectedIssue(request);
        allow(actor, "signal-issue-request", 30);
        return BrowserSignalController.GrantResponse.from(signals.issueExpectedContext(
                actor, journey, input.anchorId(), input.expectation()));
    }

    private UUID actor(HttpServletRequest request) {
        UUID actor;
        try {
            actor = sessions.authenticate(BrowserCookies.read(request, policy, "routiqo_session"));
        } catch (DataAccessException | TransactionException unavailable) {
            throw new ChoiceTransportUnavailable();
        }
        var expected = request.getHeaders("X-Routiqo-Account");
        if (!expected.hasMoreElements() || !actor.toString().equals(expected.nextElement())
                || expected.hasMoreElements()) throw new SecurityException("Account context changed");
        return actor;
    }

    private void allow(UUID actor, String category, int limit) {
        try {
            if (!rates.allow(actor.toString(), category, limit)) throw new ChoiceRequestLimited();
        } catch (ChoiceRequestLimited limited) {
            throw limited;
        } catch (RuntimeException unavailable) {
            throw new ChoiceTransportUnavailable();
        }
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new IllegalArgumentException(
                "Invalid signal choice request");
    }

    private static UUID id(String value) {
        if (value == null || !value.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Invalid signal choice identifier");
        }
        UUID parsed = UUID.fromString(value);
        if (NIL_ID.equals(parsed)) throw new IllegalArgumentException(
                "Invalid signal choice identifier");
        return parsed;
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Void> unauthenticated() { return ResponseEntity.status(401).build(); }
    @ExceptionHandler(JourneyNotFound.class)
    ResponseEntity<Void> missing() { return ResponseEntity.notFound().build(); }
    @ExceptionHandler({PrivateAnchorChoicesUnavailable.class, SignalStorageDenied.class,
            SignalStorageConflict.class})
    ResponseEntity<Void> conflict() { return ResponseEntity.status(409).build(); }
    @ExceptionHandler({SignalStorageRateLimited.class, ChoiceRequestLimited.class})
    ResponseEntity<Void> limited() {
        return ResponseEntity.status(429).header("Retry-After", "60").build();
    }
    @ExceptionHandler({AccountWriteUnavailable.class, ChoiceTransportUnavailable.class})
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
    @ExceptionHandler({IllegalArgumentException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Void> invalid() { return ResponseEntity.badRequest().build(); }

    private static final class ChoiceRequestLimited extends RuntimeException {}
    private static final class ChoiceTransportUnavailable extends RuntimeException {}
}
