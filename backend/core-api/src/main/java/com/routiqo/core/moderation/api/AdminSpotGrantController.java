package com.routiqo.core.moderation.api;

import com.routiqo.core.security.ConditionalOnExactlyTrue;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.moderation.infrastructure.AdminSessionService;
import com.routiqo.core.moderation.infrastructure.JdbcSpotGrantAdmin;
import com.routiqo.core.security.BrowserAuthPolicy;
import com.routiqo.core.security.BrowserCookies;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

@RestController
@RequestMapping("/api/v1/admin/spot-grants")
@Profile("web-auth & persistence & google-auth")
/** Spots shift grants for the moderator rota (ADR 0075). */
@ConditionalOnExactlyTrue({"ROUTIQO_ADMIN_ENABLED", "ROUTIQO_SPOTS_GRANT_ADMIN_ENABLED"})
public final class AdminSpotGrantController {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final AdminSessionService sessions;
    private final JdbcSpotGrantAdmin grants;
    private final BrowserAuthPolicy policy;
    private final AuthRateGate rates;
    public AdminSpotGrantController(AdminSessionService sessions, JdbcSpotGrantAdmin grants,
            AdminAccessConfiguration.AdminSettings settings, AuthRateGate rates) {
        this.sessions = sessions; this.grants = grants; this.policy = settings.policy(); this.rates = rates;
    }
    public record Capability(boolean canManageGrants) {}
    private record Input(UUID requestId, String permission, String reason, Integer durationMinutes) {}

    @GetMapping("/me") Capability capability(HttpServletRequest request) {
        noQuery(request);
        UUID actor = actor(request);
        allow(actor, "admin-spot-grant-read", 30);
        return new Capability(grants.canManage(actor, recheck(actor, request)));
    }
    @GetMapping("/{targetId}") JdbcSpotGrantAdmin.Review review(@PathVariable String targetId,
            HttpServletRequest request) {
        noQuery(request);
        UUID actor = actor(request);
        allow(actor, "admin-spot-grant-read", 30);
        return grants.review(actor, AdminTrafficJson.id(targetId), recheck(actor, request));
    }
    @PostMapping("/{targetId}/issue") JdbcSpotGrantAdmin.Receipt issue(@PathVariable String targetId,
            HttpServletRequest request) { return change(targetId, "ISSUE", request); }
    @PostMapping("/{targetId}/revoke") JdbcSpotGrantAdmin.Receipt revoke(@PathVariable String targetId,
            HttpServletRequest request) { return change(targetId, "REVOKE", request); }
    private JdbcSpotGrantAdmin.Receipt change(String targetId, String action, HttpServletRequest request) {
        noQuery(request);
        UUID actor = actor(request);
        Input input = input(request, action);
        allow(actor, "admin-spot-grant-write", 10);
        return grants.change(actor, AdminTrafficJson.id(targetId), input.requestId(), input.permission(),
                action, input.reason(), input.durationMinutes(), recheck(actor, request));
    }
    private Input input(HttpServletRequest request, String action) {
        try {
            byte[] body = request.getInputStream().readNBytes(513);
            if (body.length > 512) throw new IllegalArgumentException();
            String content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            JsonNode node = JSON.readTree(content);
            Set<String> expected = action.equals("ISSUE")
                    ? Set.of("requestId", "permission", "reason", "durationMinutes")
                    : Set.of("requestId", "permission", "reason");
            if (node == null || !node.isObject() || !node.propertyNames().equals(expected)
                    || !node.get("requestId").isTextual() || !node.get("permission").isTextual()
                    || !node.get("reason").isTextual() || action.equals("ISSUE") && !node.get("durationMinutes").isInt())
                throw new IllegalArgumentException();
            return new Input(AdminTrafficJson.id(node.get("requestId").textValue()),
                    node.get("permission").textValue(), node.get("reason").textValue(),
                    action.equals("ISSUE") ? node.get("durationMinutes").intValue() : null);
        } catch (IOException | RuntimeException malformed) { throw new IllegalArgumentException("Invalid grant request"); }
    }
    private UUID actor(HttpServletRequest request) { return sessions.authenticate(credential(request)).accountId(); }
    private Runnable recheck(UUID actor, HttpServletRequest request) {
        String credential = credential(request);
        return () -> sessions.assertCurrent(actor, credential);
    }
    private String credential(HttpServletRequest request) {
        return BrowserCookies.read(request, policy, "routiqo_admin_session");
    }
    private void allow(UUID actor, String category, int limit) {
        try { if (!rates.allow(actor.toString(), category, limit)) throw new JdbcSpotGrantAdmin.Limited(); }
        catch (JdbcSpotGrantAdmin.Limited limited) { throw limited; }
        catch (RuntimeException unavailable) { throw new Unavailable(); }
    }
    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new IllegalArgumentException("Invalid query");
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<Void> unauthenticated() { return ResponseEntity.status(401).build(); }
    @ExceptionHandler(JdbcSpotGrantAdmin.Denied.class) ResponseEntity<Void> denied() { return ResponseEntity.status(403).build(); }
    @ExceptionHandler(JdbcSpotGrantAdmin.Missing.class) ResponseEntity<Void> missing() { return ResponseEntity.notFound().build(); }
    @ExceptionHandler(JdbcSpotGrantAdmin.Conflict.class) ResponseEntity<Void> conflict() { return ResponseEntity.status(409).build(); }
    @ExceptionHandler(JdbcSpotGrantAdmin.Limited.class) ResponseEntity<Void> limited() {
        return ResponseEntity.status(429).header("Retry-After", "60").build();
    }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Void> bad() { return ResponseEntity.badRequest().build(); }
    @ExceptionHandler({DataAccessException.class, Unavailable.class, IllegalStateException.class})
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
    private static final class Unavailable extends RuntimeException {}
}
