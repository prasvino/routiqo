package com.routiqo.core.routeupdate.api;

import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.journey.application.JourneyNotFound;
import com.routiqo.core.routeupdate.application.CatalogSignalService;
import com.routiqo.core.routeupdate.application.SignalStorageConflict;
import com.routiqo.core.routeupdate.application.SignalStorageDenied;
import com.routiqo.core.routeupdate.application.SignalStorageRateLimited;
import com.routiqo.core.routeupdate.domain.QuickSignalReceipt;
import com.routiqo.core.routeupdate.domain.SignalCommandGrant;
import com.routiqo.core.routeupdate.domain.SignalCommandStopResult;
import com.routiqo.core.security.BrowserAuthPolicy;
import com.routiqo.core.security.BrowserCookies;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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

@RestController
@RequestMapping("/api/v1/journeys/{id}")
@Profile("web-auth & routing & persistence")
@ConditionalOnProperty(name = "ROUTIQO_LIVE_SIGNAL_API_ENABLED", havingValue = "true")
public final class BrowserSignalController {
    private static final UUID NIL_ID = new UUID(0, 0);
    private static final Duration EVIDENCE_LIFETIME = Duration.ofMinutes(15);
    private static final Duration RECEIPT_RETENTION = Duration.ofHours(24);
    private final CatalogSignalService signals;
    private final GoogleSessionService sessions;
    private final BrowserAuthPolicy policy;
    private final AuthRateGate rates;

    public BrowserSignalController(CatalogSignalService signals, GoogleSessionService sessions,
            BrowserAuthPolicy policy, AuthRateGate rates) {
        this.signals = signals;
        this.sessions = sessions;
        this.policy = policy;
        this.rates = rates;
    }

    public record GrantResponse(UUID commandId, UUID anchorId, UUID contextId,
            String routeRevision, String consentGeneration, List<String> categories,
            Instant issuedAt, Instant expiresAt) {
        static GrantResponse from(SignalCommandGrant grant) {
            var admission = grant.admission();
            List<String> categories = admission.permittedCategories().stream()
                    .map(value -> value.name().toLowerCase(Locale.ROOT)).sorted().toList();
            return new GrantResponse(grant.commandId(), admission.anchorId(),
                    admission.contextId(), Long.toString(admission.routeRevision()),
                    Long.toString(admission.consentGeneration()), categories,
                    admission.issuedAt(), admission.expiresAt());
        }

        @Override public String toString() { return "BrowserSignalGrant[private]"; }
    }

    public record ReceiptResponse(UUID commandId, String status, Instant receivedAt,
            Instant expiresAt, Instant retainUntil) {
        static ReceiptResponse from(QuickSignalReceipt receipt) {
            String status = switch (receipt.state()) {
                case ACTIVE -> "accepted";
                case WITHDRAWN -> "withdrawn";
                case SUPERSEDED -> "superseded";
            };
            return new ReceiptResponse(receipt.signal().signalId(), status,
                    receipt.signal().receivedAt(), receipt.signal().expiresAt(),
                    receipt.retainUntil());
        }

        @Override public String toString() { return "BrowserSignalReceipt[private]"; }
    }

    public record StopResponse(UUID commandId, String status, ReceiptResponse receipt) {
        static StopResponse from(SignalCommandStopResult result) {
            return new StopResponse(result.commandId(), "stopped",
                    result.receipt().map(ReceiptResponse::from).orElse(null));
        }

        @Override public String toString() { return "BrowserSignalStop[private]"; }
    }

    @PostMapping("/signal-commands") GrantResponse issue(@PathVariable String id,
            HttpServletRequest request) {
        noQuery(request);
        UUID actor = actor(request);
        UUID journey = id(id);
        UUID anchor = BrowserSignalJson.issue(request);
        allow(actor, "signal-issue-request", 30);
        return GrantResponse.from(signals.issue(actor, journey, anchor));
    }

    @PostMapping("/signals/{commandId}") ReceiptResponse accept(@PathVariable String id,
            @PathVariable String commandId, HttpServletRequest request) {
        noQuery(request);
        UUID actor = actor(request);
        UUID journey = id(id);
        UUID command = id(commandId);
        BrowserSignalJson.Acceptance input = BrowserSignalJson.acceptance(journey, request);
        allow(actor, "signal-accept-request", 60);
        return ReceiptResponse.from(signals.accept(actor, command, input.fingerprint(),
                EVIDENCE_LIFETIME, RECEIPT_RETENTION));
    }

    @PostMapping("/signals/{commandId}/withdraw") ReceiptResponse withdraw(
            @PathVariable String id, @PathVariable String commandId,
            HttpServletRequest request) {
        noQuery(request);
        UUID actor = actor(request);
        UUID journey = id(id);
        UUID command = id(commandId);
        BrowserSignalJson.empty(request);
        allow(actor, "signal-withdraw-request", 30);
        return ReceiptResponse.from(signals.withdraw(actor, journey, command));
    }

    @PostMapping("/signal-commands/{commandId}/stop") StopResponse stop(
            @PathVariable String id, @PathVariable String commandId,
            HttpServletRequest request) {
        noQuery(request);
        UUID actor = actor(request);
        UUID journey = id(id);
        UUID command = id(commandId);
        BrowserSignalJson.empty(request);
        allow(actor, "signal-stop-request", 30);
        return StopResponse.from(signals.stopCommand(actor, journey, command));
    }

    private UUID actor(HttpServletRequest request) {
        UUID actor;
        try {
            actor = sessions.authenticate(BrowserCookies.read(request, policy, "routiqo_session"));
        } catch (DataAccessException | TransactionException unavailable) {
            throw new SignalTransportUnavailable();
        }
        var expected = request.getHeaders("X-Routiqo-Account");
        if (!expected.hasMoreElements() || !actor.toString().equals(expected.nextElement())
                || expected.hasMoreElements()) throw new SecurityException("Account context changed");
        return actor;
    }

    private void allow(UUID actor, String category, int limit) {
        try {
            if (!rates.allow(actor.toString(), category, limit)) throw new SignalRequestLimited();
        } catch (SignalRequestLimited limited) {
            throw limited;
        } catch (RuntimeException unavailable) {
            throw new SignalTransportUnavailable();
        }
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new IllegalArgumentException(
                "Invalid signal request");
    }

    private static UUID id(String value) {
        if (value == null || !value.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Invalid signal identifier");
        }
        UUID parsed = UUID.fromString(value);
        if (NIL_ID.equals(parsed)) throw new IllegalArgumentException("Invalid signal identifier");
        return parsed;
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Void> unauthenticated() { return ResponseEntity.status(401).build(); }

    @ExceptionHandler(JourneyNotFound.class)
    ResponseEntity<Void> missing() { return ResponseEntity.notFound().build(); }

    @ExceptionHandler({SignalStorageDenied.class, SignalStorageConflict.class})
    ResponseEntity<Void> conflict() { return ResponseEntity.status(409).build(); }

    @ExceptionHandler({SignalStorageRateLimited.class, SignalRequestLimited.class})
    ResponseEntity<Void> limited() {
        return ResponseEntity.status(429).header("Retry-After", "60").build();
    }

    @ExceptionHandler({AccountWriteUnavailable.class, SignalTransportUnavailable.class})
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }

    @ExceptionHandler({IllegalArgumentException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Void> invalid() { return ResponseEntity.badRequest().build(); }

    private static final class SignalRequestLimited extends RuntimeException {}
    private static final class SignalTransportUnavailable extends RuntimeException {}
}
