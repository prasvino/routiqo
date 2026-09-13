package com.routiqo.core.privacy.api;

import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.journey.application.JourneyNotFound;
import com.routiqo.core.privacy.application.PresenceConsentConflict;
import com.routiqo.core.privacy.application.PresenceConsentService;
import com.routiqo.core.privacy.domain.PresenceConsent;
import com.routiqo.core.security.BrowserAuthPolicy;
import com.routiqo.core.security.BrowserCookies;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/journeys/{id}/consent")
@Profile("web-auth")
@ConditionalOnProperty(name = "ROUTIQO_LIVE_CONSENT_API_ENABLED", havingValue = "true")
public final class BrowserConsentController {
    private static final UUID NIL_ID = new UUID(0, 0);
    private final PresenceConsentService consents;
    private final GoogleSessionService sessions;
    private final BrowserAuthPolicy policy;
    private final AuthRateGate rates;

    public BrowserConsentController(PresenceConsentService consents, GoogleSessionService sessions,
            BrowserAuthPolicy policy, AuthRateGate rates) {
        this.consents = consents;
        this.sessions = sessions;
        this.policy = policy;
        this.rates = rates;
    }

    public record ConsentResponse(
            UUID journeyId, String generation, boolean sharing, boolean journeyActive) {
        static ConsentResponse from(PresenceConsent consent) {
            return new ConsentResponse(consent.journeyId(), Long.toString(consent.generation()),
                    consent.sharing(), consent.journeyActive());
        }

        @Override public String toString() { return "BrowserConsentResponse[private]"; }
    }

    @GetMapping ConsentResponse read(@PathVariable String id, HttpServletRequest request) {
        noQuery(request);
        UUID actor = actor(request);
        allow(actor, "consent-read-account", 60);
        return ConsentResponse.from(consents.read(actor, id(id)));
    }

    @PostMapping ConsentResponse submit(@PathVariable String id, HttpServletRequest request) {
        noQuery(request);
        UUID actor = actor(request);
        BrowserConsentJson.Intent intent = BrowserConsentJson.intent(request);
        allow(actor, intent.sharing() ? "consent-enable-account" : "consent-disable-account",
                intent.sharing() ? 10 : 20);
        return ConsentResponse.from(consents.submitIntent(
                actor, id(id), intent.expectedGeneration(), intent.sharing()));
    }

    private UUID actor(HttpServletRequest request) {
        UUID actor;
        try {
            actor = sessions.authenticate(BrowserCookies.read(request, policy, "routiqo_session"));
        } catch (DataAccessException | TransactionException unavailable) {
            throw new ConsentUnavailable();
        }
        var expected = request.getHeaders("X-Routiqo-Account");
        if (!expected.hasMoreElements() || !actor.toString().equals(expected.nextElement())
                || expected.hasMoreElements()) {
            throw new SecurityException("Account context changed");
        }
        return actor;
    }

    private void allow(UUID actor, String category, int limit) {
        try {
            if (!rates.allow(actor.toString(), category, limit)) {
                throw new TooManyRequests();
            }
        } catch (TooManyRequests limited) {
            throw limited;
        } catch (RuntimeException unavailable) {
            throw new ConsentUnavailable();
        }
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw new IllegalArgumentException("Invalid consent request");
        }
    }

    private static UUID id(String value) {
        if (value == null
                || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Invalid journey identifier");
        }
        UUID id = UUID.fromString(value);
        if (NIL_ID.equals(id)) {
            throw new IllegalArgumentException("Invalid journey identifier");
        }
        return id;
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Void> unauthenticated() { return ResponseEntity.status(401).build(); }

    @ExceptionHandler(JourneyNotFound.class)
    ResponseEntity<Void> missing() { return ResponseEntity.notFound().build(); }

    @ExceptionHandler(PresenceConsentConflict.class)
    ResponseEntity<Void> conflict() { return ResponseEntity.status(409).build(); }

    @ExceptionHandler(TooManyRequests.class)
    ResponseEntity<Void> limited() {
        return ResponseEntity.status(429).header("Retry-After", "60").build();
    }

    @ExceptionHandler({AccountWriteUnavailable.class, ConsentUnavailable.class})
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }

    @ExceptionHandler({IllegalArgumentException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Void> invalid() { return ResponseEntity.badRequest().build(); }

    private static final class TooManyRequests extends RuntimeException {}
    private static final class ConsentUnavailable extends RuntimeException {}
}
