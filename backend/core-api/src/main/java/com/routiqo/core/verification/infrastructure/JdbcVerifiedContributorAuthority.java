package com.routiqo.core.verification.infrastructure;

import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.verification.application.VerificationParticipant;
import com.routiqo.core.verification.application.VerifiedContributorReader;
import com.routiqo.core.verification.application.VerifiedContributorService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** DB-backed case review and read authority; no identity evidence enters this module. */
public final class JdbcVerifiedContributorAuthority implements VerificationParticipant, VerifiedContributorReader {
    private static final UUID NIL = new UUID(0, 0);
    private final JdbcTemplate jdbc;

    public JdbcVerifiedContributorAuthority(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<VerifiedContributor> current(UUID accountId, Instant now) {
        return readCurrent(accountId, now, false);
    }

    @Override
    public Optional<VerifiedContributor> currentForFrozenShare(UUID accountId, Instant now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Frozen Share authority transaction required");
        return readCurrent(accountId, now, true);
    }

    private Optional<VerifiedContributor> readCurrent(UUID accountId, Instant now, boolean lock) {
        if (accountId == null || NIL.equals(accountId) || now == null) return Optional.empty();
        try {
            List<VerifiedContributor> rows = jdbc.query("""
                SELECT v.person_ref, v.revision, v.expires_at
                FROM live_verified_contributor v
                JOIN routiqo_account a ON a.id = v.account_id
                WHERE v.account_id = ? AND v.state = 'active' AND a.enabled = TRUE
                  AND v.expires_at > ?
                """ + (lock ? " FOR SHARE OF v" : ""), (rs, row) -> new VerifiedContributor(
                        rs.getObject("person_ref", UUID.class), rs.getLong("revision"),
                        rs.getTimestamp("expires_at").toInstant()), accountId, Timestamp.from(now));
            return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    @Override
    public UUID targetForCase(UUID caseId) {
        try {
            List<UUID> rows = jdbc.query("SELECT account_id FROM live_verification_case WHERE id = ?",
                    (rs, row) -> rs.getObject(1, UUID.class), caseId);
            if (rows.size() != 1) throw denied();
            return rows.getFirst();
        } catch (DataAccessException unavailable) {
            throw new AccountWriteUnavailable();
        }
    }

    @Override
    public VerifiedContributorService.Receipt apply(UUID reviewerId, UUID targetId,
            VerifiedContributorService.Command command, Clock clock) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Verification transaction required");
        try {
            return applyLocked(reviewerId, targetId, command, clock);
        } catch (DataAccessException unavailable) {
            throw new AccountWriteUnavailable();
        }
    }

    private VerifiedContributorService.Receipt applyLocked(UUID reviewerId, UUID targetId,
            VerifiedContributorService.Command command, Clock clock) {
        List<Case> cases = jdbc.query("""
            SELECT account_id, person_ref, expires_at FROM live_verification_case
            WHERE id = ? FOR UPDATE
            """, (rs, row) -> new Case(rs.getObject("account_id", UUID.class),
                    rs.getObject("person_ref", UUID.class), rs.getTimestamp("expires_at").toInstant()),
                command.caseId());
        if (cases.size() != 1 || !cases.getFirst().target().equals(targetId)) throw denied();
        Case verificationCase = cases.getFirst();
        String action = command.action().name().toLowerCase(java.util.Locale.ROOT);
        List<Grant> grants = jdbc.query("""
            SELECT issued_at, expires_at FROM live_verification_reviewer_grant
            WHERE reviewer_id = ? AND case_id = ? AND action = ? FOR UPDATE
            """, (rs, row) -> new Grant(rs.getTimestamp(1).toInstant(),
                    rs.getTimestamp(2).toInstant()), reviewerId, command.caseId(), action);
        List<State> states = jdbc.query("""
            SELECT case_id, person_ref, revision, state, first_reviewer_id, expires_at
            FROM live_verified_contributor WHERE account_id = ? FOR UPDATE
            """, (rs, row) -> new State(rs.getObject("case_id", UUID.class),
                    rs.getObject("person_ref", UUID.class), rs.getLong("revision"),
                    rs.getString("state"), rs.getObject("first_reviewer_id", UUID.class),
                    rs.getTimestamp("expires_at").toInstant()), targetId);
        List<Audit> audits = jdbc.query("""
            SELECT case_id, action, before_revision, after_revision, active, occurred_at, expires_at
            FROM live_verification_audit WHERE reviewer_id = ? AND request_id = ? FOR UPDATE
            """, (rs, row) -> new Audit(rs.getObject("case_id", UUID.class), rs.getString("action"),
                    rs.getLong("before_revision"), rs.getLong("after_revision"),
                    rs.getBoolean("active"), rs.getTimestamp("occurred_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant()), reviewerId, command.requestId());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (grants.size() != 1 || grants.getFirst().issuedAt().isAfter(now)
                || !now.isBefore(grants.getFirst().expiresAt())
                || grants.getFirst().expiresAt().isAfter(grants.getFirst().issuedAt().plusSeconds(86400)))
            throw denied();
        if (!audits.isEmpty()) {
            Audit audit = audits.getFirst();
            if (!audit.caseId().equals(command.caseId()) || !audit.action().equals(action)
                    || audit.beforeRevision() != command.expectedRevision()
                    || audit.occurredAt().isAfter(now) || !now.isBefore(audit.expiresAt()))
                throw denied();
            return new VerifiedContributorService.Receipt(command.caseId(), audit.afterRevision(),
                    audit.active());
        }
        if (command.action() == VerifiedContributorService.Action.REVIEW
                && !now.isBefore(verificationCase.expiresAt())) throw denied();
        State prior = states.isEmpty() ? null : states.getFirst();
        long revision = prior == null ? 0 : prior.revision();
        if (revision != command.expectedRevision() || revision == Long.MAX_VALUE) throw denied();
        int future = jdbc.queryForObject("""
            SELECT count(*) FROM (
                SELECT 1 FROM live_verification_audit WHERE reviewer_id = ?
                  AND occurred_at > ? LIMIT 1
            ) future
            """, Integer.class, reviewerId, Timestamp.from(now));
        if (future != 0) throw denied();
        int recent = jdbc.queryForObject("""
            SELECT count(*) FROM (
                SELECT 1 FROM live_verification_audit WHERE reviewer_id = ?
                  AND occurred_at > ? LIMIT 21
            ) recent
            """, Integer.class, reviewerId, Timestamp.from(now.minusSeconds(3600)));
        if (recent >= 20) throw denied();
        int retained = jdbc.queryForObject("""
            SELECT count(*) FROM (
                SELECT 1 FROM live_verification_audit WHERE reviewer_id = ? LIMIT 1001
            ) retained
            """, Integer.class, reviewerId);
        if (retained >= 1000) throw denied();

        boolean active;
        if (command.action() == VerifiedContributorService.Action.REVIEW) {
            if (prior == null || "revoked".equals(prior.state())) {
                if (prior != null && prior.caseId().equals(command.caseId())) throw denied();
                if (jdbc.update("""
                    INSERT INTO live_verified_contributor(account_id, case_id, person_ref, revision,
                        state, first_reviewer_id, second_reviewer_id, expires_at)
                    VALUES (?, ?, ?, ?, 'pending', ?, NULL, ?)
                    ON CONFLICT (account_id) DO UPDATE SET case_id = EXCLUDED.case_id,
                        person_ref = EXCLUDED.person_ref, revision = EXCLUDED.revision,
                        state = 'pending', first_reviewer_id = EXCLUDED.first_reviewer_id,
                        second_reviewer_id = NULL, expires_at = EXCLUDED.expires_at
                    """, targetId, command.caseId(), verificationCase.personRef(), revision + 1,
                        reviewerId, Timestamp.from(verificationCase.expiresAt())) != 1) throw denied();
                active = false;
            } else if ("pending".equals(prior.state())
                    && prior.caseId().equals(command.caseId())
                    && prior.personRef().equals(verificationCase.personRef())
                    && prior.expiresAt().equals(verificationCase.expiresAt())
                    && !prior.firstReviewer().equals(reviewerId)) {
                if (jdbc.update("""
                    UPDATE live_verified_contributor SET state = 'active', revision = ?,
                        second_reviewer_id = ? WHERE account_id = ? AND revision = ?
                    """, revision + 1, reviewerId, targetId, revision) != 1) throw denied();
                active = true;
            } else throw denied();
        } else {
            if (prior == null || !"active".equals(prior.state())
                    || !prior.caseId().equals(command.caseId())) throw denied();
            if (jdbc.update("""
                UPDATE live_verified_contributor SET state = 'revoked', revision = ?
                WHERE account_id = ? AND revision = ?
                """, revision + 1, targetId, revision) != 1) throw denied();
            active = false;
        }
        if (jdbc.update("""
            INSERT INTO live_verification_audit(reviewer_id, request_id, case_id, account_id,
                action, before_revision, after_revision, active, occurred_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, reviewerId, command.requestId(), command.caseId(), targetId, action,
                revision, revision + 1, active, Timestamp.from(now),
                Timestamp.from(now.plusSeconds(90L * 86400))) != 1) throw denied();
        return new VerifiedContributorService.Receipt(command.caseId(), revision + 1, active);
    }

    private static SecurityException denied() { return new SecurityException("Verification denied"); }
    private record Case(UUID target, UUID personRef, Instant expiresAt) {}
    private record Grant(Instant issuedAt, Instant expiresAt) {}
    private record State(UUID caseId, UUID personRef, long revision, String state,
                         UUID firstReviewer, Instant expiresAt) {}
    private record Audit(UUID caseId, String action, long beforeRevision, long afterRevision,
                         boolean active, Instant occurredAt, Instant expiresAt) {}
}
