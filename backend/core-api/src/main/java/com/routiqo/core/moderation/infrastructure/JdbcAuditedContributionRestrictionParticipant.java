package com.routiqo.core.moderation.infrastructure;

import com.routiqo.core.moderation.application.AuditedContributionRestrictionParticipant;
import com.routiqo.core.moderation.application.AuditedContributionRestrictionService;
import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.moderation.domain.ContributorAssessment;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Atomic grant, replay, quota, restriction-CAS and minimized-audit persistence participant. */
public final class JdbcAuditedContributionRestrictionParticipant
        implements AuditedContributionRestrictionParticipant {
    private static final Duration GRANT_MAX = Duration.ofHours(24);
    private static final Duration ACTION_WINDOW = Duration.ofHours(1);
    private static final Duration AUDIT_RETENTION = Duration.ofDays(30);
    private static final int AUDIT_CAPACITY = 1000;
    private static final int ACTION_SLOTS = 20;
    private final JdbcTemplate jdbc;
    private final JdbcContributionRestrictionParticipant restrictions;

    public JdbcAuditedContributionRestrictionParticipant(JdbcTemplate jdbc,
            JdbcContributionRestrictionParticipant restrictions) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.restrictions = Objects.requireNonNull(restrictions);
    }

    @Override
    public AuditedContributionRestrictionService.Receipt apply(UUID operatorId,
            AuditedContributionRestrictionService.Command command, Clock clock) {
        requireTransaction();
        Objects.requireNonNull(operatorId);
        Objects.requireNonNull(command);
        Objects.requireNonNull(clock);
        try {
            return applyWithinTransaction(operatorId, command, clock);
        } catch (DataAccessException unavailable) {
            throw new AccountWriteUnavailable();
        }
    }

    private AuditedContributionRestrictionService.Receipt applyWithinTransaction(UUID operatorId,
            AuditedContributionRestrictionService.Command command, Clock clock) {
        String permission = command.action() == AuditedContributionRestrictionService.Action.RESTRICT
                ? "restrict" : "restore";
        List<Grant> grants = jdbc.query("""
            SELECT issued_at, expires_at FROM moderation_operator_grant
            WHERE operator_id = ? AND permission = ? FOR UPDATE
            """, (row, number) -> new Grant(
                    row.getTimestamp("issued_at").toInstant(),
                    row.getTimestamp("expires_at").toInstant()),
                operatorId, permission);
        List<Audit> audits = jdbc.query("""
            SELECT target_id, expected_revision, action, reason, before_revision,
                   after_revision, restricted, issued_at, expires_at
            FROM moderation_contribution_audit
            WHERE operator_id = ? AND request_id = ? FOR UPDATE
            """, (row, number) -> new Audit(
                    row.getObject("target_id", UUID.class), row.getLong("expected_revision"),
                    row.getString("action"), row.getString("reason"),
                    row.getLong("before_revision"), row.getLong("after_revision"),
                    row.getBoolean("restricted"),
                    row.getTimestamp("issued_at").toInstant(),
                    row.getTimestamp("expires_at").toInstant()),
                operatorId, command.requestId());
        List<Slot> slots = jdbc.query("""
            SELECT slot, used_at FROM moderation_restriction_action_slot
            WHERE operator_id = ? ORDER BY slot FOR UPDATE
            """, (row, number) -> new Slot(row.getInt("slot"),
                    row.getTimestamp("used_at").toInstant()), operatorId);

        Instant now = now(clock);
        if (grants.size() != 1 || !current(grants.getFirst(), now)) throw denied();
        if (!audits.isEmpty()) return replay(command, audits.getFirst(), now);
        if (slots.stream().anyMatch(slot -> slot.usedAt().isAfter(now))) throw denied();
        if (auditCount(operatorId) >= AUDIT_CAPACITY) {
            throw new IllegalStateException("Audited restriction capacity reached");
        }

        ContributorAssessment prior = restrictions.read(command.targetId());
        if (prior == null || !command.targetId().equals(prior.actorId())
                || prior.revision() != command.expectedRevision()
                || prior.revision() == Long.MAX_VALUE) throw denied();
        ContributorAssessment updated = command.action()
                == AuditedContributionRestrictionService.Action.RESTRICT
                        ? prior.suspend(command.expectedRevision())
                        : prior.unsuspend(command.expectedRevision());
        if (updated == prior || updated.revision() <= prior.revision()) throw denied();

        reserveSlot(operatorId, slots, now);
        restrictions.replace(prior, updated);
        Instant expiresAt;
        try {
            expiresAt = now.plus(AUDIT_RETENTION);
        } catch (DateTimeException invalid) {
            throw denied();
        }
        int inserted = jdbc.update("""
            INSERT INTO moderation_contribution_audit(
                operator_id, request_id, target_id, expected_revision, action, reason,
                before_revision, after_revision, restricted, issued_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, operatorId, command.requestId(), command.targetId(), command.expectedRevision(),
                permission, reason(command.reason()), prior.revision(), updated.revision(),
                updated.state() == ContributorAssessment.State.SUSPENDED,
                Timestamp.from(now), Timestamp.from(expiresAt));
        if (inserted != 1) throw denied();
        return receipt(command, prior.revision(), updated.revision(),
                updated.state() == ContributorAssessment.State.SUSPENDED, now, expiresAt);
    }

    private AuditedContributionRestrictionService.Receipt replay(
            AuditedContributionRestrictionService.Command command, Audit audit, Instant now) {
        if (!audit.targetId().equals(command.targetId())
                || audit.expectedRevision() != command.expectedRevision()
                || !audit.action().equals(command.action() ==
                        AuditedContributionRestrictionService.Action.RESTRICT ? "restrict" : "restore")
                || !audit.reason().equals(reason(command.reason()))
                || audit.issuedAt().isAfter(now) || !now.isBefore(audit.expiresAt())) throw denied();
        return receipt(command, audit.beforeRevision(), audit.afterRevision(), audit.restricted(),
                audit.issuedAt(), audit.expiresAt());
    }

    private int auditCount(UUID operatorId) {
        return jdbc.query("""
            SELECT request_id FROM moderation_contribution_audit
            WHERE operator_id = ? LIMIT 1001
            """, (row, number) -> row.getObject(1, UUID.class), operatorId).size();
    }

    private void reserveSlot(UUID operatorId, List<Slot> slots, Instant now) {
        if (slots.size() < ACTION_SLOTS) {
            boolean[] occupied = new boolean[ACTION_SLOTS];
            for (Slot slot : slots) {
                if (slot.slot() < 0 || slot.slot() >= ACTION_SLOTS || occupied[slot.slot()])
                    throw denied();
                occupied[slot.slot()] = true;
            }
            int free = 0;
            while (occupied[free]) free++;
            if (jdbc.update("""
                    INSERT INTO moderation_restriction_action_slot(operator_id, slot, used_at)
                    VALUES (?, ?, ?)
                    """, operatorId, free, Timestamp.from(now)) != 1) throw denied();
            return;
        }
        if (slots.size() != ACTION_SLOTS) throw denied();
        Slot oldest = slots.stream().min(java.util.Comparator.comparing(Slot::usedAt)).orElseThrow();
        if (oldest.usedAt().isAfter(now.minus(ACTION_WINDOW))) {
            throw new IllegalStateException("Audited restriction quota reached");
        }
        if (jdbc.update("""
                UPDATE moderation_restriction_action_slot SET used_at = ?
                WHERE operator_id = ? AND slot = ? AND used_at = ?
                """, Timestamp.from(now), operatorId, oldest.slot(),
                Timestamp.from(oldest.usedAt())) != 1) throw denied();
    }

    private static boolean current(Grant grant, Instant now) {
        Duration validity = Duration.between(grant.issuedAt(), grant.expiresAt());
        return !grant.issuedAt().isAfter(now) && now.isBefore(grant.expiresAt())
                && !validity.isZero() && !validity.isNegative()
                && validity.compareTo(GRANT_MAX) <= 0;
    }

    private static Instant now(Clock clock) {
        try {
            Instant value = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (value.equals(Instant.MIN) || value.equals(Instant.MAX)) throw denied();
            return value;
        } catch (RuntimeException unavailable) {
            throw denied();
        }
    }

    private static String reason(AuditedContributionRestrictionService.Reason reason) {
        return reason.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static AuditedContributionRestrictionService.Receipt receipt(
            AuditedContributionRestrictionService.Command command, long before, long after,
            boolean restricted, Instant issued, Instant expires) {
        return new AuditedContributionRestrictionService.Receipt(command.requestId(),
                command.targetId(), before, after, restricted, command.action(), command.reason(),
                issued, expires);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Audited restriction transaction is required");
        }
    }

    private static SecurityException denied() {
        return new SecurityException("Audited contribution restriction denied");
    }

    private record Grant(Instant issuedAt, Instant expiresAt) {}
    private record Slot(int slot, Instant usedAt) {}
    private record Audit(UUID targetId, long expectedRevision, String action, String reason,
            long beforeRevision, long afterRevision, boolean restricted,
            Instant issuedAt, Instant expiresAt) {}
}
