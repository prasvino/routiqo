package com.routiqo.core.publiclive.api;

import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.journey.application.JourneyNotFound;
import com.routiqo.core.publiclive.application.PublicSignalIntentConflict;
import com.routiqo.core.publiclive.application.PublicSignalIntentService;
import com.routiqo.core.security.BrowserAuthPolicy;
import com.routiqo.core.security.BrowserCookies;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Private owner command transport. Never returns candidates or public moments. */
@RestController
@RequestMapping("/api/v1/journeys/{id}/signals/{commandId}/public-intent")
@Profile("web-auth & routing & persistence")
@ConditionalOnProperty(name = {"ROUTIQO_PUBLIC_SIGNAL_INTENT_API_ENABLED",
        "ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED"}, havingValue = "true")
public final class BrowserPublicSignalIntentController {
    private final PublicSignalIntentService intents;
    private final GoogleSessionService sessions;
    private final BrowserAuthPolicy policy;
    private final AuthRateGate rates;
    private final boolean shareEnabled;

    public BrowserPublicSignalIntentController(PublicSignalIntentService intents,
            GoogleSessionService sessions, BrowserAuthPolicy policy, AuthRateGate rates,
            @Value("${ROUTIQO_PUBLIC_SIGNAL_INTENT_SHARE_ENABLED:false}") boolean shareEnabled) {
        this.intents = intents;
        this.sessions = sessions;
        this.policy = policy;
        this.rates = rates;
        this.shareEnabled = shareEnabled;
    }

    public record ShareResponse(UUID commandId, String status, Instant sharedAt) {
        @Override public String toString() { return "BrowserPublicShare[private]"; }
    }

    public record StopResponse(UUID commandId, String status) {
        @Override public String toString() { return "BrowserPublicStop[private]"; }
    }

    @PostMapping ShareResponse share(@PathVariable String id, @PathVariable String commandId,
            HttpServletRequest request) {
        if (!shareEnabled) throw new ShareDisabled();
        noQuery(request);
        UUID actor = actor(request);
        UUID journey = BrowserPublicSignalIntentJson.id(id);
        UUID command = BrowserPublicSignalIntentJson.id(commandId);
        UUID requestId = BrowserPublicSignalIntentJson.share(request);
        allow(actor, "public-signal-share-request", 12);
        var result = intents.share(actor, journey, command, requestId);
        return new ShareResponse(result.commandId(), "shared", result.sharedAt());
    }

    @PostMapping("/stop") StopResponse stop(@PathVariable String id,
            @PathVariable String commandId, HttpServletRequest request) {
        noQuery(request);
        UUID actor = actor(request);
        UUID journey = BrowserPublicSignalIntentJson.id(id);
        UUID command = BrowserPublicSignalIntentJson.id(commandId);
        BrowserPublicSignalIntentJson.stop(request);
        allow(actor, "public-signal-stop-request", 30);
        intents.stop(actor, journey, command);
        return new StopResponse(command, "stopped");
    }

    private UUID actor(HttpServletRequest request) {
        UUID actor;
        try {
            actor = sessions.authenticate(BrowserCookies.read(request, policy, "routiqo_session"));
        } catch (DataAccessException | TransactionException unavailable) {
            throw new TransportUnavailable();
        }
        var expected = request.getHeaders("X-Routiqo-Account");
        if (!expected.hasMoreElements() || !actor.toString().equals(expected.nextElement())
                || expected.hasMoreElements()) throw new SecurityException("Account context changed");
        return actor;
    }

    private void allow(UUID actor, String category, int limit) {
        try {
            if (!rates.allow(actor.toString(), category, limit)) throw new RequestLimited();
        } catch (RequestLimited limited) {
            throw limited;
        } catch (RuntimeException unavailable) {
            throw new TransportUnavailable();
        }
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new IllegalArgumentException(
                "Invalid public intent request");
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Void> unauthenticated() { return ResponseEntity.status(401).build(); }
    @ExceptionHandler(JourneyNotFound.class)
    ResponseEntity<Void> missing() { return ResponseEntity.notFound().build(); }
    @ExceptionHandler(PublicSignalIntentConflict.class)
    ResponseEntity<Void> conflict() { return ResponseEntity.status(409).build(); }
    @ExceptionHandler(RequestLimited.class)
    ResponseEntity<Void> limited() {
        return ResponseEntity.status(429).header("Retry-After", "60").build();
    }
    @ExceptionHandler({AccountWriteUnavailable.class, TransportUnavailable.class,
            DataAccessException.class, TransactionException.class})
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Void> invalid() { return ResponseEntity.badRequest().build(); }
    @ExceptionHandler(ShareDisabled.class)
    ResponseEntity<Void> disabled() { return ResponseEntity.status(403).build(); }

    private static final class RequestLimited extends RuntimeException {}
    private static final class TransportUnavailable extends RuntimeException {}
    private static final class ShareDisabled extends RuntimeException {}
}
