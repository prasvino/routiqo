package com.routiqo.core.planning.api;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.planning.application.PlanningAccountUnavailable;
import com.routiqo.core.planning.application.PlanningBackupService;
import com.routiqo.core.planning.application.PlanningConflict;
import com.routiqo.core.planning.application.PlanningDocumentTooLarge;
import com.routiqo.core.planning.domain.AccountPlanningCopy;
import com.routiqo.core.planning.domain.PlanningPlan;
import com.routiqo.core.security.BrowserAuthPolicy;
import com.routiqo.core.security.BrowserCookies;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner-only account planning copy (ADR 0062). Explicit browser actions only; no background sync. */
@RestController
@RequestMapping("/api/v1/planning")
@Profile("web-auth & persistence")
@ConditionalOnProperty(name = "ROUTIQO_PLANNING_BACKUP_API_ENABLED", havingValue = "true")
public final class BrowserPlanningController {
    static final int WRITES_PER_MINUTE = 20;
    private final PlanningBackupService planning;
    private final GoogleSessionService sessions;
    private final BrowserAuthPolicy policy;
    private final AuthRateGate rates;

    public BrowserPlanningController(PlanningBackupService planning, GoogleSessionService sessions,
            BrowserAuthPolicy policy, AuthRateGate rates) {
        this.planning = planning; this.sessions = sessions; this.policy = policy; this.rates = rates;
    }

    public record PlanResponse(String id, String kind, String origin, String destination, String date, String time,
            List<Integer> days, String notes, String createdAt) {
        static PlanResponse from(PlanningPlan plan) {
            return new PlanResponse(plan.id(), plan.kind().wire(), plan.origin(), plan.destination(), plan.date(),
                    plan.time(), plan.days(), plan.notes(), plan.createdAt());
        }
        @Override public String toString() { return "PlanResponse[private]"; }
    }

    public record PlanningResponse(boolean present, long version, Instant updatedAt, List<PlanResponse> plans,
            List<String> saved) {
        static PlanningResponse from(AccountPlanningCopy copy) {
            return new PlanningResponse(copy.present(), copy.version(), copy.updatedAt(),
                    copy.document().plans().stream().map(PlanResponse::from).toList(), copy.document().saved());
        }
        @Override public String toString() { return "PlanningResponse[private]"; }
    }

    @GetMapping PlanningResponse get(HttpServletRequest request) {
        return PlanningResponse.from(planning.get(actor(request)));
    }

    @PostMapping PlanningResponse save(HttpServletRequest request) throws IOException {
        UUID actor = writer(request);
        return PlanningResponse.from(planning.save(actor, BrowserPlanningJson.save(request.getInputStream())));
    }

    @PostMapping("/delete") PlanningResponse delete(HttpServletRequest request) throws IOException {
        UUID actor = writer(request);
        return PlanningResponse.from(planning.delete(actor, BrowserPlanningJson.delete(request.getInputStream())));
    }

    private UUID writer(HttpServletRequest request) {
        UUID actor = actor(request);
        if (!rates.allow(actor.toString(), "planning-write-account", WRITES_PER_MINUTE)) throw new TooManyRequests();
        return actor;
    }

    private UUID actor(HttpServletRequest request) {
        UUID actor = sessions.authenticate(BrowserCookies.read(request, policy, "routiqo_session"));
        var expected = request.getHeaders("X-Routiqo-Account");
        if (!expected.hasMoreElements() || !actor.toString().equals(expected.nextElement()) || expected.hasMoreElements())
            throw new SecurityException("Account context changed");
        return actor;
    }

    @ExceptionHandler({SecurityException.class, PlanningAccountUnavailable.class})
    ResponseEntity<Void> unauthenticated() { return ResponseEntity.status(401).build(); }
    @ExceptionHandler(PlanningConflict.class) ResponseEntity<Void> conflict() { return ResponseEntity.status(409).build(); }
    @ExceptionHandler(PlanningDocumentTooLarge.class) ResponseEntity<Void> tooLarge() {
        return ResponseEntity.status(413).build();
    }
    @ExceptionHandler(TooManyRequests.class) ResponseEntity<Void> limited() {
        return ResponseEntity.status(429).header("Retry-After", "60").build();
    }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Void> invalid() {
        return ResponseEntity.badRequest().build();
    }

    private static final class TooManyRequests extends RuntimeException {}
}
