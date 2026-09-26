package com.routiqo.core.moderation.api;

import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.moderation.application.ModeratorRestrictionService;
import com.routiqo.core.moderation.application.OperatorNotPermitted;
import com.routiqo.core.moderation.infrastructure.AdminSessionService;
import com.routiqo.core.security.BrowserAuthPolicy;
import com.routiqo.core.security.BrowserCookies;
import com.routiqo.core.security.ConditionalOnExactlyTrue;
import com.routiqo.core.spot.application.SpotContributionConflict;
import com.routiqo.core.spot.application.SpotContributionNotFound;
import com.routiqo.core.spot.application.SpotModerationService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The Spots moderator queue and decisions (PILOT_MODERATION_SPEC.md, ADR 0075). */
@RestController
@RequestMapping("/api/v1/admin/spots")
@Profile("web-auth & native-auth & routing & persistence & google-auth")
@ConditionalOnExactlyTrue({"ROUTIQO_ADMIN_ENABLED", "ROUTIQO_SPOTS_ADMIN_ENABLED", "ROUTIQO_SPOTS_API_ENABLED",
    "ROUTIQO_SPOTS_CONTRIBUTIONS_ENABLED"})
public final class AdminSpotController {
    static final int READS_PER_MINUTE = 30;
    static final int WRITES_PER_MINUTE = 10;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final AdminSessionService sessions;
    private final SpotModerationService moderation;
    private final ModeratorRestrictionService restrictions;
    private final BrowserAuthPolicy policy;
    private final AuthRateGate rates;

    public AdminSpotController(AdminSessionService sessions, SpotModerationService moderation,
            ModeratorRestrictionService restrictions, AdminAccessConfiguration.AdminSettings settings,
            AuthRateGate rates) {
        this.sessions = sessions; this.moderation = moderation; this.restrictions = restrictions;
        this.policy = settings.policy(); this.rates = rates;
    }

    record Input(UUID requestId, String reason, long reportVersion) {}
    record RestrictionInput(String accountRef, UUID requestId, long expectedRevision, String reason) {}
    public record RestrictionResponse(boolean restricted, long revision) {}

    @GetMapping("/reports") SpotModerationService.Queue queue(HttpServletRequest request) {
        if (request.getParameterMap().keySet().stream().anyMatch(key -> !key.equals("cursor"))
                || request.getParameterValues("cursor") != null && request.getParameterValues("cursor").length != 1)
            throw new IllegalArgumentException("Invalid query");
        UUID operator = operator(request);
        AdminHttp.allow(rates, operator, "admin-spot-read", READS_PER_MINUTE);
        return moderation.queue(operator, request.getParameter("cursor"), recheck(operator, request));
    }

    @PostMapping("/reports/{ref}/dismiss") SpotModerationService.Decision dismiss(@PathVariable String ref,
            HttpServletRequest request) { return decide(ref, SpotModerationService.Action.DISMISS, request); }
    @PostMapping("/reports/{ref}/hide") SpotModerationService.Decision hide(@PathVariable String ref,
            HttpServletRequest request) { return decide(ref, SpotModerationService.Action.HIDE, request); }
    @PostMapping("/reports/{ref}/restore") SpotModerationService.Decision restore(@PathVariable String ref,
            HttpServletRequest request) { return decide(ref, SpotModerationService.Action.RESTORE, request); }
    @PostMapping("/reports/{ref}/clear-signals") SpotModerationService.Decision clearSignals(@PathVariable String ref,
            HttpServletRequest request) { return decide(ref, SpotModerationService.Action.CLEAR_SIGNALS, request); }

    @PostMapping("/reports/{ref}/author") SpotModerationService.AuthorLookup author(@PathVariable String ref,
            HttpServletRequest request) {
        AdminHttp.noQuery(request);
        UUID operator = operator(request);
        Input input = input(request, false);
        AdminHttp.allow(rates, operator, "admin-spot-write", WRITES_PER_MINUTE);
        return moderation.lookup(operator, input.requestId(), AdminTrafficJson.id(ref), input.reason(),
                recheck(operator, request));
    }

    /** The account reference travels in the body, so it never lands in a URL, log line or history. */
    @PostMapping("/accounts/restrict") RestrictionResponse restrict(HttpServletRequest request) {
        return restriction(request, true);
    }
    @PostMapping("/accounts/restore") RestrictionResponse restoreAccount(HttpServletRequest request) {
        return restriction(request, false);
    }

    private RestrictionResponse restriction(HttpServletRequest request, boolean restrict) {
        AdminHttp.noQuery(request);
        UUID operator = operator(request);
        RestrictionInput input = restrictionInput(request);
        AdminHttp.allow(rates, operator, "admin-spot-write", WRITES_PER_MINUTE);
        Runnable session = recheck(operator, request);
        UUID account = moderation.accountForRestriction(operator, input.accountRef(), session);
        // The change runs in its own transaction: re-check the session and the reference inside it.
        var result = restrictions.apply(operator, input.requestId(), account, input.expectedRevision(), restrict,
                input.reason(), () -> {
                    session.run();
                    moderation.requireAccountRef(operator, input.accountRef(), account);
                });
        return new RestrictionResponse(result.restricted(), result.revision());
    }

    static RestrictionInput restrictionInput(HttpServletRequest request) {
        try {
            JsonNode node = JSON.readTree(utf8(request));
            if (node == null || !node.isObject()
                    || !node.propertyNames().equals(Set.of("accountRef", "requestId", "expectedRevision", "reason"))
                    || !node.get("accountRef").isTextual() || !node.get("requestId").isTextual()
                    || !node.get("expectedRevision").isIntegralNumber() || !node.get("expectedRevision").canConvertToLong()
                    || !node.get("reason").isTextual())
                throw new IllegalArgumentException();
            return new RestrictionInput(node.get("accountRef").textValue(),
                    AdminTrafficJson.id(node.get("requestId").textValue()), node.get("expectedRevision").longValue(),
                    node.get("reason").textValue());
        } catch (IOException | RuntimeException malformed) {
            throw new IllegalArgumentException("Invalid restriction request");
        }
    }

    private SpotModerationService.Decision decide(String ref, SpotModerationService.Action action,
            HttpServletRequest request) {
        AdminHttp.noQuery(request);
        UUID operator = operator(request);
        Input input = input(request, true);
        AdminHttp.allow(rates, operator, "admin-spot-write", WRITES_PER_MINUTE);
        return moderation.decide(operator, input.requestId(), AdminTrafficJson.id(ref), input.reportVersion(),
                action, input.reason(), recheck(operator, request));
    }

    /**
     * Exactly {"requestId", "reason"} plus, for decisions, the queue item's "reportVersion"; at most 512
     * bytes of UTF-8.
     */
    static Input input(HttpServletRequest request, boolean decision) {
        try {
            JsonNode node = JSON.readTree(utf8(request));
            Set<String> expected = decision ? Set.of("requestId", "reason", "reportVersion")
                    : Set.of("requestId", "reason");
            if (node == null || !node.isObject() || !node.propertyNames().equals(expected)
                    || !node.get("requestId").isTextual() || !node.get("reason").isTextual()
                    || decision && (!node.get("reportVersion").isIntegralNumber()
                        || !node.get("reportVersion").canConvertToLong()))
                throw new IllegalArgumentException();
            return new Input(AdminTrafficJson.id(node.get("requestId").textValue()), node.get("reason").textValue(),
                    decision ? node.get("reportVersion").longValue() : 0);
        } catch (IOException | RuntimeException malformed) {
            throw new IllegalArgumentException("Invalid decision request");
        }
    }

    private static String utf8(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(513);
        if (body.length > 512) throw new IllegalArgumentException();
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
    }

    private UUID operator(HttpServletRequest request) { return sessions.authenticate(credential(request)).accountId(); }
    private Runnable recheck(UUID operator, HttpServletRequest request) {
        String credential = credential(request);
        return () -> sessions.assertCurrent(operator, credential);
    }
    private String credential(HttpServletRequest request) {
        return BrowserCookies.read(request, policy, "routiqo_admin_session");
    }

    @ExceptionHandler(SecurityException.class) ResponseEntity<Void> unauthenticated() { return ResponseEntity.status(401).build(); }
    @ExceptionHandler(OperatorNotPermitted.class) ResponseEntity<Void> denied() { return ResponseEntity.status(403).build(); }
    @ExceptionHandler(SpotContributionNotFound.class) ResponseEntity<Void> missing() { return ResponseEntity.notFound().build(); }
    @ExceptionHandler({SpotContributionConflict.class, ModeratorRestrictionService.Refused.class})
    ResponseEntity<Void> conflict() { return ResponseEntity.status(409).build(); }
    @ExceptionHandler(AdminHttp.Limited.class) ResponseEntity<Void> limited() {
        return ResponseEntity.status(429).header("Retry-After", "60").build();
    }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<Void> bad() { return ResponseEntity.badRequest().build(); }
    @ExceptionHandler({DataAccessException.class, AdminHttp.Unavailable.class, AccountWriteUnavailable.class,
        IllegalStateException.class})
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
}
