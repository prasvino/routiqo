package com.routiqo.core.planning.infrastructure;

import com.routiqo.core.planning.application.PlanningAccountUnavailable;
import com.routiqo.core.planning.application.PlanningConflict;
import com.routiqo.core.planning.application.PlanningCopyStore;
import com.routiqo.core.planning.application.PlanningDocumentTooLarge;
import com.routiqo.core.planning.domain.AccountPlanningCopy;
import com.routiqo.core.planning.domain.PlanningDocument;
import com.routiqo.core.planning.domain.PlanningMutation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One row per account. Removing a copy keeps a content-free tombstone with the next version, so versions
 * never repeat for an account and a stale device cannot match a copy saved after a removal.
 */
public final class JdbcPlanningCopyStore implements PlanningCopyStore {
    private static final String COLUMNS =
            "present, plans::text AS plans, saved::text AS saved, version, updated_at, latest_mutation_id";
    private static final PlanningDocumentJson.Encoded EMPTY = PlanningDocumentJson.encode(PlanningDocument.empty());
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcPlanningCopyStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transaction.setTimeout(5);
    }

    @Override
    public AccountPlanningCopy find(UUID accountId) {
        Objects.requireNonNull(accountId);
        return rows(accountId, false).stream().findFirst().map(Stored::copy).orElseGet(AccountPlanningCopy::absent);
    }

    @Override
    public AccountPlanningCopy save(UUID accountId, PlanningMutation mutation, Instant now) {
        Objects.requireNonNull(accountId); Objects.requireNonNull(mutation); Objects.requireNonNull(now);
        PlanningDocumentJson.Encoded encoded = PlanningDocumentJson.encode(mutation.document());
        if (encoded.bytes() > PlanningDocument.MAX_BYTES) throw new PlanningDocumentTooLarge();
        Instant storedAt = now.truncatedTo(ChronoUnit.MICROS);
        try {
            return Objects.requireNonNull(transaction.execute(status -> saveLocked(accountId, mutation, encoded, storedAt)));
        } catch (DuplicateKeyException concurrentFirstSave) {
            // A concurrent first save won after both transactions observed no row. Reconcile in a fresh transaction.
            return Objects.requireNonNull(transaction.execute(status -> reconcileLocked(accountId, mutation)));
        } catch (DataIntegrityViolationException invalid) {
            if (hasSqlState(invalid, "23503")) throw new PlanningAccountUnavailable();
            throw invalid;
        }
    }

    @Override
    public AccountPlanningCopy delete(UUID accountId, long expectedVersion, Instant now) {
        Objects.requireNonNull(accountId); Objects.requireNonNull(now);
        Instant storedAt = now.truncatedTo(ChronoUnit.MICROS);
        return Objects.requireNonNull(transaction.execute(status -> {
            Optional<Stored> existing = rows(accountId, true).stream().findFirst();
            if (existing.isEmpty()) return AccountPlanningCopy.absent();
            AccountPlanningCopy current = existing.get().copy();
            if (!current.present()) return current;
            if (current.version() != expectedVersion || current.version() >= AccountPlanningCopy.MAX_VERSION)
                throw new PlanningConflict();
            long next = current.version() + 1;
            // A fresh mutation identity ensures no earlier save can be replayed against the tombstone.
            int changed = jdbc.update("""
                UPDATE account_planning_copy
                SET present = FALSE, plans = CAST(? AS jsonb), saved = CAST(? AS jsonb), document_bytes = ?,
                    version = ?, updated_at = ?, latest_mutation_id = ?
                WHERE account_id = ? AND version = ?
                """, EMPTY.plans(), EMPTY.saved(), EMPTY.bytes(), next, Timestamp.from(storedAt), UUID.randomUUID(),
                    accountId, current.version());
            if (changed != 1) throw new PlanningConflict();
            return AccountPlanningCopy.absent(next);
        }));
    }

    private AccountPlanningCopy saveLocked(UUID accountId, PlanningMutation mutation,
            PlanningDocumentJson.Encoded encoded, Instant now) {
        Optional<Stored> existing = rows(accountId, true).stream().findFirst();
        if (existing.isPresent()) return update(existing.get(), accountId, mutation, encoded, now);
        if (mutation.expectedVersion() != 0) throw new PlanningConflict();
        jdbc.update("""
            INSERT INTO account_planning_copy
                (account_id, present, plans, saved, document_bytes, version, updated_at, latest_mutation_id)
            VALUES (?, TRUE, CAST(? AS jsonb), CAST(? AS jsonb), ?, 1, ?, ?)
            """, accountId, encoded.plans(), encoded.saved(), encoded.bytes(), Timestamp.from(now), mutation.mutationId());
        return AccountPlanningCopy.present(mutation.document(), 1, now);
    }

    private AccountPlanningCopy reconcileLocked(UUID accountId, PlanningMutation mutation) {
        Stored existing = rows(accountId, true).stream().findFirst().orElseThrow(PlanningConflict::new);
        return replay(existing, mutation).orElseThrow(PlanningConflict::new);
    }

    private AccountPlanningCopy update(Stored existing, UUID accountId, PlanningMutation mutation,
            PlanningDocumentJson.Encoded encoded, Instant now) {
        Optional<AccountPlanningCopy> replay = replay(existing, mutation);
        if (replay.isPresent()) return replay.get();
        long current = existing.copy().version();
        if (existing.latestMutationId().equals(mutation.mutationId())
                || mutation.expectedVersion() != current
                || current >= AccountPlanningCopy.MAX_VERSION)
            throw new PlanningConflict();
        long next = current + 1;
        int changed = jdbc.update("""
            UPDATE account_planning_copy
            SET present = TRUE, plans = CAST(? AS jsonb), saved = CAST(? AS jsonb), document_bytes = ?, version = ?,
                updated_at = ?, latest_mutation_id = ?
            WHERE account_id = ? AND version = ?
            """, encoded.plans(), encoded.saved(), encoded.bytes(), next, Timestamp.from(now), mutation.mutationId(),
                accountId, current);
        if (changed != 1) throw new PlanningConflict();
        return AccountPlanningCopy.present(mutation.document(), next, now);
    }

    private static Optional<AccountPlanningCopy> replay(Stored existing, PlanningMutation mutation) {
        return existing.copy().present()
                && existing.latestMutationId().equals(mutation.mutationId())
                && existing.copy().version() == mutation.expectedVersion() + 1
                && existing.copy().document().equals(mutation.document())
                ? Optional.of(existing.copy()) : Optional.empty();
    }

    private List<Stored> rows(UUID accountId, boolean lock) {
        return jdbc.query("SELECT " + COLUMNS + " FROM account_planning_copy WHERE account_id = ?"
                + (lock ? " FOR UPDATE" : ""), JdbcPlanningCopyStore::map, accountId);
    }

    private static Stored map(ResultSet row, int number) throws SQLException {
        final AccountPlanningCopy copy;
        try {
            long version = row.getLong("version");
            PlanningDocument document = PlanningDocumentJson.decode(row.getString("plans"), row.getString("saved"));
            copy = row.getBoolean("present")
                    ? AccountPlanningCopy.present(document, version, row.getTimestamp("updated_at").toInstant())
                    : new AccountPlanningCopy(document, version, null, false);
        } catch (RuntimeException corrupt) {
            // Never report stored-data corruption as a client input error, and never echo its content.
            throw new IllegalStateException("Stored planning copy is invalid");
        }
        return new Stored(copy, row.getObject("latest_mutation_id", UUID.class));
    }

    private static boolean hasSqlState(Throwable error, String state) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) return true;
        return false;
    }

    private record Stored(AccountPlanningCopy copy, UUID latestMutationId) {}
}
