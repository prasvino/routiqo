package com.routiqo.core.publiclive.api;

import com.routiqo.core.security.ConditionalOnExactlyTrue;
import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.publiclive.application.PublicSignalIntentService;
import com.routiqo.core.publiclive.application.PublicSignalIntentStore;
import com.routiqo.core.security.BrowserAuthPolicy;
import com.routiqo.core.security.BrowserCookies;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Account-wide private Stop recovery, including completed journeys. */
@RestController
@RequestMapping("/api/v1/public-intents")
@Profile("web-auth & routing & persistence")
@ConditionalOnExactlyTrue({"ROUTIQO_PUBLIC_SIGNAL_INTENT_API_ENABLED",
        "ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED"})
public final class BrowserPublicSignalIntentListController {
    private final PublicSignalIntentService intents;
    private final GoogleSessionService sessions;
    private final BrowserAuthPolicy policy;
    private final AuthRateGate rates;

    public BrowserPublicSignalIntentListController(PublicSignalIntentService intents,
            GoogleSessionService sessions, BrowserAuthPolicy policy, AuthRateGate rates) {
        this.intents = intents;
        this.sessions = sessions;
        this.policy = policy;
        this.rates = rates;
    }

    public record Handle(UUID journeyId, UUID commandId, String status, Instant sharedAt) {
        static Handle from(PublicSignalIntentStore.Handle handle) {
            return new Handle(handle.journeyId(), handle.commandId(),
                    handle.state() == PublicSignalIntentStore.State.ACTIVE ? "shared" : "stopped",
                    handle.sharedAt());
        }
        @Override public String toString() { return "BrowserPublicIntentHandle[private]"; }
    }
    public record Page(List<Handle> intents, String nextCursor) {
        public Page { intents = List.copyOf(intents); }
        @Override public String toString() { return "BrowserPublicIntentPage[private]"; }
    }

    @GetMapping Page list(HttpServletRequest request) {
        UUID actor = actor(request);
        var cursor = cursor(request.getQueryString());
        allow(actor);
        var page = intents.list(actor, cursor);
        return new Page(page.handles().stream().map(Handle::from).toList(),
                encode(page.nextCursor()));
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

    private void allow(UUID actor) {
        try {
            if (!rates.allow(actor.toString(), "public-signal-intent-list-request", 30))
                throw new RequestLimited();
        } catch (RequestLimited limited) {
            throw limited;
        } catch (RuntimeException unavailable) {
            throw new TransportUnavailable();
        }
    }

    private static PublicSignalIntentStore.Cursor cursor(String query) {
        if (query == null) return null;
        if (!query.matches("cursor=[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException();
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(query.substring(7)),
                    StandardCharsets.UTF_8);
            int boundary = decoded.indexOf('|');
            if (boundary < 0 || boundary != decoded.lastIndexOf('|'))
                throw new IllegalArgumentException();
            Instant instant = Instant.parse(decoded.substring(0, boundary));
            UUID command = BrowserPublicSignalIntentJson.id(decoded.substring(boundary + 1));
            return new PublicSignalIntentStore.Cursor(instant, command);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid public intent cursor");
        }
    }

    private static String encode(PublicSignalIntentStore.Cursor cursor) {
        if (cursor == null) return null;
        String source = cursor.sharedAt() + "|" + cursor.commandId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(source.getBytes(StandardCharsets.UTF_8));
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Void> unauthenticated() { return ResponseEntity.status(401).build(); }
    @ExceptionHandler(RequestLimited.class)
    ResponseEntity<Void> limited() {
        return ResponseEntity.status(429).header("Retry-After", "60").build();
    }
    @ExceptionHandler({AccountWriteUnavailable.class, TransportUnavailable.class,
            DataAccessException.class, TransactionException.class})
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Void> invalid() { return ResponseEntity.badRequest().build(); }

    private static final class RequestLimited extends RuntimeException {}
    private static final class TransportUnavailable extends RuntimeException {}
}
