package com.routiqo.core.publiclive.api;

import com.routiqo.core.security.ConditionalOnExactlyTrue;
import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.journey.application.JourneyNotFound;
import com.routiqo.core.publiclive.application.CommunityTrafficCandidateStore;
import com.routiqo.core.publiclive.application.CommunityTrafficConflict;
import com.routiqo.core.publiclive.application.CommunityTrafficShareService;
import com.routiqo.core.security.BrowserAuthPolicy;
import com.routiqo.core.security.BrowserCookies;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
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

/** Owner V3 command and recovery transport; never exposes contributor lists or source rows. */
@RestController
@Profile("web-auth & routing & persistence")
@ConditionalOnExactlyTrue({"ROUTIQO_COMMUNITY_TRAFFIC_V3_ENABLED",
        "ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED"})
public final class BrowserCommunityTrafficController {
    private final CommunityTrafficShareService service;
    private final GoogleSessionService sessions;
    private final BrowserAuthPolicy policy;
    private final AuthRateGate rates;

    public BrowserCommunityTrafficController(CommunityTrafficShareService service,
            GoogleSessionService sessions, BrowserAuthPolicy policy, AuthRateGate rates) {
        this.service = service;
        this.sessions = sessions;
        this.policy = policy;
        this.rates = rates;
    }

    public record Handle(UUID candidateId, UUID journeyId, UUID commandId, UUID requestId,
            String status, Instant acceptedAt, Instant windowEndsAt) {
        static Handle of(CommunityTrafficCandidateStore.Candidate candidate) {
            return new Handle(candidate.candidateId(), candidate.journeyId(), candidate.commandId(),
                    candidate.requestId(), candidate.state() == CommunityTrafficCandidateStore.State.STOPPED
                            ? "stopped_for_future_sharing"
                            : !Instant.now().isBefore(candidate.expiresAt()) ? "expired"
                            : "accepted_for_consideration",
                    candidate.acceptedAt(), candidate.windowEndsAt());
        }
    }
    public record ShareResponse(UUID candidateId, UUID commandId, String status,
            Instant acceptedAt, Instant windowEndsAt) {}
    public record StopResponse(UUID commandId, String status) {}

    @PostMapping("/api/v1/journeys/{id}/signals/{commandId}/community-share")
    ShareResponse share(@PathVariable String id, @PathVariable String commandId,
            HttpServletRequest request) {
        noQuery(request);
        UUID actor = actor(request);
        UUID journey = BrowserCommunityTrafficJson.id(id);
        UUID command = BrowserCommunityTrafficJson.id(commandId);
        UUID requestId = BrowserCommunityTrafficJson.share(request);
        allow(actor, "community-traffic-v3-share", 12);
        var candidate = service.share(actor, journey, command, requestId);
        Handle handle = Handle.of(candidate);
        return new ShareResponse(handle.candidateId(), handle.commandId(), handle.status(),
                handle.acceptedAt(), handle.windowEndsAt());
    }

    @PostMapping("/api/v1/journeys/{id}/signals/{commandId}/community-share/stop")
    StopResponse stop(@PathVariable String id, @PathVariable String commandId,
            HttpServletRequest request) {
        noQuery(request);
        UUID actor = actor(request);
        UUID journey = BrowserCommunityTrafficJson.id(id);
        UUID command = BrowserCommunityTrafficJson.id(commandId);
        BrowserCommunityTrafficJson.stop(request);
        allow(actor, "community-traffic-v3-stop", 30);
        service.stop(actor, journey, command);
        return new StopResponse(command, "stopped_for_future_sharing");
    }

    @GetMapping("/api/v1/community-shares")
    List<Handle> recover(HttpServletRequest request) {
        noQuery(request);
        UUID actor = actor(request);
        allow(actor, "community-traffic-v3-recover", 30);
        return service.recover(actor).stream().map(Handle::of).toList();
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
        if (request.getQueryString() != null)
            throw new IllegalArgumentException("Invalid community traffic request");
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Void> unauthenticated() { return ResponseEntity.status(401).build(); }
    @ExceptionHandler(JourneyNotFound.class)
    ResponseEntity<Void> missing() { return ResponseEntity.notFound().build(); }
    @ExceptionHandler(CommunityTrafficConflict.class)
    ResponseEntity<Void> conflict() { return ResponseEntity.status(409).build(); }
    @ExceptionHandler(RequestLimited.class)
    ResponseEntity<Void> limited() { return ResponseEntity.status(429).header("Retry-After", "60").build(); }
    @ExceptionHandler({AccountWriteUnavailable.class, TransportUnavailable.class,
            DataAccessException.class, TransactionException.class})
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Void> invalid() { return ResponseEntity.badRequest().build(); }

    private static final class RequestLimited extends RuntimeException {}
    private static final class TransportUnavailable extends RuntimeException {}
}
