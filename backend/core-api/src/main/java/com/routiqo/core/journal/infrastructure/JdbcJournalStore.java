package com.routiqo.core.journal.infrastructure;

import com.routiqo.core.journal.application.JournalConflict;
import com.routiqo.core.journal.application.JournalStore;
import com.routiqo.core.journal.domain.JournalAnnotation;
import com.routiqo.core.journal.domain.JournalMutation;
import com.routiqo.core.journey.application.JourneyNotFound;
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

public final class JdbcJournalStore implements JournalStore {
    private static final String COLUMNS = "title, notes, version, updated_at, latest_mutation_id";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcJournalStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transaction.setTimeout(5);
    }

    @Override
    public Optional<JournalAnnotation> find(UUID actorId, UUID journeyId) {
        Objects.requireNonNull(actorId); Objects.requireNonNull(journeyId);
        return rows(actorId, journeyId, false).stream().findFirst().map(Stored::annotation);
    }

    @Override
    public JournalAnnotation save(UUID actorId, UUID journeyId, JournalMutation mutation, Instant now) {
        Objects.requireNonNull(actorId); Objects.requireNonNull(journeyId);
        Objects.requireNonNull(mutation); Objects.requireNonNull(now);
        Instant storedAt = now.truncatedTo(ChronoUnit.MICROS);
        try {
            return Objects.requireNonNull(transaction.execute(status -> saveLocked(actorId, journeyId, mutation, storedAt)));
        } catch (DuplicateKeyException concurrentFirstSave) {
            // A concurrent first save won after both transactions observed no row. Reconcile in a fresh transaction.
            return Objects.requireNonNull(transaction.execute(status -> reconcileLocked(actorId, journeyId, mutation)));
        } catch (DataIntegrityViolationException invalid) {
            if (hasSqlState(invalid, "23503")) throw new JourneyNotFound();
            throw invalid;
        }
    }

    private JournalAnnotation saveLocked(UUID actorId, UUID journeyId, JournalMutation mutation, Instant now) {
        Optional<Stored> existing = rows(actorId, journeyId, true).stream().findFirst();
        if (existing.isPresent()) return update(existing.get(), actorId, journeyId, mutation, now);
        if (mutation.expectedVersion() != 0) throw new JournalConflict();
        jdbc.update("""
            INSERT INTO journey_journal_annotation
                (journey_id, account_id, title, notes, version, updated_at, latest_mutation_id)
            VALUES (?, ?, ?, ?, 1, ?, ?)
            """, journeyId, actorId, mutation.title(), mutation.notes(), Timestamp.from(now), mutation.mutationId());
        return new JournalAnnotation(mutation.title(), mutation.notes(), 1, now);
    }

    private JournalAnnotation reconcileLocked(UUID actorId, UUID journeyId, JournalMutation mutation) {
        Stored existing = rows(actorId, journeyId, true).stream().findFirst().orElseThrow(JournalConflict::new);
        return replay(existing, mutation).orElseThrow(JournalConflict::new);
    }

    private JournalAnnotation update(Stored existing, UUID actorId, UUID journeyId, JournalMutation mutation, Instant now) {
        Optional<JournalAnnotation> replay = replay(existing, mutation);
        if (replay.isPresent()) return replay.get();
        if (existing.latestMutationId().equals(mutation.mutationId())
                || mutation.expectedVersion() != existing.annotation().version()
                || existing.annotation().version() >= JournalAnnotation.MAX_VERSION)
            throw new JournalConflict();
        long nextVersion = existing.annotation().version() + 1;
        int changed = jdbc.update("""
            UPDATE journey_journal_annotation
            SET title = ?, notes = ?, version = ?, updated_at = ?, latest_mutation_id = ?
            WHERE journey_id = ? AND account_id = ? AND version = ?
            """, mutation.title(), mutation.notes(), nextVersion, Timestamp.from(now), mutation.mutationId(),
                journeyId, actorId, existing.annotation().version());
        if (changed != 1) throw new JournalConflict();
        return new JournalAnnotation(mutation.title(), mutation.notes(), nextVersion, now);
    }

    private static Optional<JournalAnnotation> replay(Stored existing, JournalMutation mutation) {
        return existing.latestMutationId().equals(mutation.mutationId())
                && existing.annotation().version() == mutation.expectedVersion() + 1
                && existing.annotation().title().equals(mutation.title())
                && existing.annotation().notes().equals(mutation.notes())
                ? Optional.of(existing.annotation()) : Optional.empty();
    }

    private List<Stored> rows(UUID actorId, UUID journeyId, boolean lock) {
        return jdbc.query("SELECT " + COLUMNS + " FROM journey_journal_annotation"
                        + " WHERE account_id = ? AND journey_id = ?" + (lock ? " FOR UPDATE" : ""),
                JdbcJournalStore::map, actorId, journeyId);
    }

    private static Stored map(ResultSet row, int number) throws SQLException {
        var annotation = new JournalAnnotation(row.getString("title"), row.getString("notes"),
                row.getLong("version"), row.getTimestamp("updated_at").toInstant());
        return new Stored(annotation, row.getObject("latest_mutation_id", UUID.class));
    }

    private static boolean hasSqlState(Throwable error, String state) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) return true;
        return false;
    }

    private record Stored(JournalAnnotation annotation, UUID latestMutationId) {}
}
