package com.routiqo.core.moderation.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spots shift grants (ADR 0069/0075): a current {@code spots_grant_admin} issues and revokes queue
 * permissions for 1 to 12 hours. Nobody grants themselves, {@code spots_grant_admin} is never issued here
 * (grant admins are created out of band), and unlike V3 a grant admin may receive queue permissions from
 * the other grant admin. Account locks precede grant locks in every operation.
 */
public final class JdbcSpotGrantAdmin {
    static final List<String> PERMISSIONS = List.of("spots_review", "spots_hide", "spots_restrict", "spots_alias_lookup");
    static final List<String> REASONS = List.of("shift_start", "coverage_change", "security_response", "error_correction");
    private static final int AUDIT_CAPACITY = 1000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public JdbcSpotGrantAdmin(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(8);
    }

    public record Grant(String permission, Instant expiresAt) {}
    public record Review(UUID targetId, List<Grant> grants) {}
    public record Receipt(UUID targetId, String permission, Instant expiresAt, UUID requestId, boolean replayed) {}
    private record Audit(UUID target, String permission, String action, String reason,
            Integer duration, Instant grantExpiry) {}
    private record Window(Instant issued, Instant expires) {
        boolean current(Instant now) { return !issued.isAfter(now) && expires.isAfter(now); }
    }

    public boolean canManage(UUID actor, Runnable sessionRecheck) {
        rejectAmbient();
        return transaction.execute(status -> {
            var enabled = jdbc.query("SELECT enabled FROM routiqo_account WHERE id = ? FOR SHARE",
                    (rs, n) -> rs.getBoolean(1), actor);
            Window grant = lockGrant(actor, "spots_grant_admin");
            sessionRecheck.run();
            Instant now = now();
            return !enabled.isEmpty() && enabled.getFirst() && grant != null && grant.current(now);
        });
    }

    public Review review(UUID actor, UUID target, Runnable sessionRecheck) {
        rejectAmbient();
        if (!canManage(actor, sessionRecheck)) throw new Denied();
        validateActorTarget(actor, target);
        return transaction.execute(status -> {
            lockAccounts(actor, target);
            Window administrator = lockGrant(actor, "spots_grant_admin");
            List<Object[]> locked = jdbc.query("""
                    SELECT permission, issued_at, expires_at FROM moderation_operator_grant
                    WHERE operator_id = ? AND permission IN
                        ('spots_review', 'spots_hide', 'spots_restrict', 'spots_alias_lookup')
                    ORDER BY permission FOR UPDATE
                    """, (rs, n) -> new Object[]{rs.getString(1), rs.getTimestamp(2).toInstant(),
                            rs.getTimestamp(3).toInstant()}, target);
            sessionRecheck.run();
            Instant now = now();
            authority(administrator, now);
            List<Grant> grants = locked.stream()
                    .filter(row -> !((Instant) row[1]).isAfter(now) && ((Instant) row[2]).isAfter(now))
                    .map(row -> new Grant((String) row[0], (Instant) row[2])).toList();
            readCapacity(actor);
            jdbc.update("""
                    INSERT INTO spot_grant_read_audit(id, administrator_id, occurred_at, expires_at)
                    VALUES (?, ?, ?, ?)
                    """, UUID.randomUUID(), actor, Timestamp.from(now), Timestamp.from(now.plus(30, ChronoUnit.DAYS)));
            return new Review(target, grants);
        });
    }

    public Receipt change(UUID actor, UUID target, UUID requestId, String permission, String action,
            String reason, Integer durationMinutes, Runnable sessionRecheck) {
        if (requestId == null || requestId.equals(new UUID(0, 0))
                || !PERMISSIONS.contains(permission)
                || !List.of("ISSUE", "REVOKE").contains(action)
                || !REASONS.contains(reason)
                || (action.equals("ISSUE") ? durationMinutes == null || durationMinutes < 60 || durationMinutes > 720
                    : durationMinutes != null)) throw new IllegalArgumentException("Invalid grant request");
        String storedReason = reason.toUpperCase(java.util.Locale.ROOT);
        rejectAmbient();
        if (!canManage(actor, sessionRecheck)) throw new Denied();
        validateActorTarget(actor, target);
        return transaction.execute(status -> {
            lockAccounts(actor, target);
            Window administrator = lockGrant(actor, "spots_grant_admin");
            List<Window> existing = jdbc.query("""
                    SELECT issued_at, expires_at FROM moderation_operator_grant
                    WHERE operator_id = ? AND permission = ? FOR UPDATE
                    """, (rs, n) -> new Window(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()),
                    target, permission);
            List<Audit> audits = jdbc.query("""
                    SELECT target_id, permission, action, reason, duration_minutes, grant_expires_at
                    FROM spot_grant_action_audit
                    WHERE administrator_id = ? AND request_id = ? FOR UPDATE
                    """, (rs, n) -> new Audit(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getObject(5, Integer.class),
                    rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()), actor, requestId);
            sessionRecheck.run();
            Instant now = now();
            authority(administrator, now);
            if (!audits.isEmpty()) {
                Audit old = audits.getFirst();
                if (!old.target().equals(target) || !old.permission().equals(permission)
                        || !old.action().equals(action) || !old.reason().equals(storedReason)
                        || !java.util.Objects.equals(old.duration(), durationMinutes)) throw new Conflict();
                return new Receipt(target, permission, old.grantExpiry(), requestId, true);
            }
            writeCapacity(actor, now);
            if (action.equals("ISSUE")) {
                if (!existing.isEmpty() && existing.getFirst().current(now)) throw new Conflict();
                Instant expires = now.plus(durationMinutes, ChronoUnit.MINUTES);
                jdbc.update("""
                        INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (operator_id, permission) DO UPDATE
                          SET issued_at = EXCLUDED.issued_at, expires_at = EXCLUDED.expires_at
                        """, target, permission, Timestamp.from(now), Timestamp.from(expires));
                audit(actor, target, requestId, permission, action, storedReason, durationMinutes, expires, now);
                return new Receipt(target, permission, expires, requestId, false);
            }
            if (existing.isEmpty() || !existing.getFirst().current(now)) throw new Conflict();
            if (jdbc.update("DELETE FROM moderation_operator_grant WHERE operator_id = ? AND permission = ?",
                    target, permission) != 1) throw new Conflict();
            audit(actor, target, requestId, permission, action, storedReason, null, null, now);
            return new Receipt(target, permission, null, requestId, false);
        });
    }

    private void audit(UUID actor, UUID target, UUID request, String permission, String action,
            String reason, Integer duration, Instant grantExpiry, Instant now) {
        jdbc.update("""
                INSERT INTO spot_grant_action_audit
                (administrator_id, request_id, target_id, permission, action, reason, duration_minutes,
                 grant_expires_at, occurred_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, actor, request, target, permission, action, reason, duration,
                grantExpiry == null ? null : Timestamp.from(grantExpiry), Timestamp.from(now),
                Timestamp.from(now.plus(30, ChronoUnit.DAYS)));
    }

    private void lockAccounts(UUID actor, UUID target) {
        UUID first = actor.compareTo(target) < 0 ? actor : target;
        UUID second = actor.compareTo(target) < 0 ? target : actor;
        lockAccount(first);
        lockAccount(second);
    }

    private void lockAccount(UUID id) {
        var enabled = jdbc.query("SELECT enabled FROM routiqo_account WHERE id = ? FOR UPDATE",
                (rs, n) -> rs.getBoolean(1), id);
        if (enabled.isEmpty() || !enabled.getFirst()) throw new Missing();
    }

    private Window lockGrant(UUID actor, String permission) {
        var rows = jdbc.query("""
                SELECT issued_at, expires_at FROM moderation_operator_grant
                WHERE operator_id = ? AND permission = ? FOR UPDATE
                """, (rs, n) -> new Window(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()), actor, permission);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static void authority(Window grant, Instant now) {
        if (grant == null || !grant.current(now)) throw new Denied();
    }

    private void writeCapacity(UUID actor, Instant now) {
        int retained = jdbc.query("SELECT request_id FROM spot_grant_action_audit WHERE administrator_id = ? LIMIT 1001",
                (rs, n) -> rs.getObject(1, UUID.class), actor).size();
        int hourly = jdbc.query("""
                SELECT request_id FROM spot_grant_action_audit
                WHERE administrator_id = ? AND occurred_at > ? LIMIT 21
                """, (rs, n) -> rs.getObject(1, UUID.class), actor, Timestamp.from(now.minus(1, ChronoUnit.HOURS))).size();
        if (retained >= AUDIT_CAPACITY || hourly >= 20) throw new Limited();
    }

    private void readCapacity(UUID actor) {
        if (jdbc.query("SELECT id FROM spot_grant_read_audit WHERE administrator_id = ? LIMIT 3001",
                (rs, n) -> rs.getObject(1, UUID.class), actor).size() >= 3000) throw new Limited();
    }

    private Instant now() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant().truncatedTo(ChronoUnit.MICROS);
    }
    private static void rejectAmbient() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Ambient grant transaction refused");
    }
    private static void validateActorTarget(UUID actor, UUID target) {
        if (actor == null || target == null || actor.equals(target) || target.equals(new UUID(0, 0))) throw new Missing();
    }
    public static final class Missing extends RuntimeException {}
    public static final class Denied extends RuntimeException {}
    public static final class Conflict extends RuntimeException {}
    public static final class Limited extends RuntimeException {}
}
