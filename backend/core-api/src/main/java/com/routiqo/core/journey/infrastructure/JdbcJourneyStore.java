package com.routiqo.core.journey.infrastructure;

import com.routiqo.core.journey.application.JourneyConflict;
import com.routiqo.core.journey.application.JourneyNotFound;
import com.routiqo.core.journey.application.JourneyStore;
import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.identity.application.AccountWriteAuthority;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JdbcJourneyStore implements JourneyStore, JourneyWriteAuthority {
    private static final String COLUMNS = "id, owner_id, kind, status, started_at, completed_at";
    private final JdbcTemplate jdbc;
    private final AccountWriteAuthority accounts;

    public JdbcJourneyStore(JdbcTemplate jdbc, AccountWriteAuthority accounts) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.accounts = Objects.requireNonNull(accounts);
    }

    @Override
    public Journey start(UUID actorId, UUID journeyId, Journey.Kind kind, Instant now) {
        Objects.requireNonNull(actorId); Objects.requireNonNull(journeyId);
        Objects.requireNonNull(kind); Objects.requireNonNull(now);
        return accounts.withEnabledAccount(actorId, () -> {
            try {
                jdbc.update("""
                    INSERT INTO journey (id, owner_id, kind, status, started_at)
                    VALUES (?, ?, ?, 'ACTIVE', ?)
                    ON CONFLICT (id) DO NOTHING
                    """, journeyId, actorId, kind.name(), timestamp(now));
                Journey stored = find(actorId, journeyId).orElseThrow(JourneyConflict::new);
                if (stored.kind() != kind) throw new JourneyConflict();
                return stored;
            } catch (DuplicateKeyException conflict) {
                // PostgreSQL's partial unique index arbitrates starts if older callers bypassed this gate.
                throw new JourneyConflict();
            }
        });
    }

    @Override
    public Optional<Journey> find(UUID actorId, UUID journeyId) {
        return findOwned(actorId, journeyId, false);
    }

    private Optional<Journey> findOwned(UUID actorId, UUID journeyId, boolean lock) {
        Objects.requireNonNull(actorId); Objects.requireNonNull(journeyId);
        List<Journey> rows = jdbc.query("SELECT " + COLUMNS +
                " FROM journey WHERE owner_id = ? AND id = ?" + (lock ? " FOR UPDATE" : ""),
                JdbcJourneyStore::map, actorId, journeyId);
        return rows.stream().findFirst();
    }

    @Override
    public Journey complete(UUID actorId, UUID journeyId, Instant now) {
        Objects.requireNonNull(now);
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Journey completion transaction is required");
        }
        Journey stored = findOwned(actorId, journeyId, true).orElseThrow(JourneyNotFound::new);
        Journey completed = stored.complete(actorId, now.truncatedTo(ChronoUnit.MICROS));
        if (stored.status() == Journey.Status.ACTIVE) {
            jdbc.update("""
                UPDATE journey SET status = 'COMPLETED', completed_at = ?
                WHERE owner_id = ? AND id = ? AND status = 'ACTIVE'
                """, timestamp(completed.completedAt()), actorId, journeyId);
        }
        return completed;
    }

    @Override
    public <T> T withOwnedJourney(UUID actorId, UUID journeyId, Work<T> work) {
        if (journeyId == null || work == null) {
            throw new JourneyNotFound();
        }
        return accounts.withEnabledAccount(actorId, () -> {
            Journey journey = findOwned(actorId, journeyId, true).orElseThrow(JourneyNotFound::new);
            return work.execute(journey);
        });
    }

    @Override
    public Page list(UUID actorId, Cursor before, int limit) {
        Objects.requireNonNull(actorId);
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Page size must be 1 to 50");
        String base = "SELECT " + COLUMNS + " FROM journey WHERE owner_id = ?";
        List<Journey> rows = before == null
                ? jdbc.query(base + " ORDER BY started_at DESC, id DESC LIMIT ?", JdbcJourneyStore::map, actorId, limit + 1)
                : jdbc.query(base + " AND (started_at, id) < (?, ?) ORDER BY started_at DESC, id DESC LIMIT ?",
                        JdbcJourneyStore::map, actorId, timestamp(before.startedAt()), before.id(), limit + 1);
        List<Journey> page = rows.stream().limit(limit).toList();
        Cursor next = rows.size() > limit ? new Cursor(page.getLast().startedAt(), page.getLast().id()) : null;
        return new Page(page, next);
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS));
    }
    private static Journey map(ResultSet row, int number) throws SQLException {
        Timestamp completed = row.getTimestamp("completed_at");
        return new Journey(row.getObject("id", UUID.class), row.getObject("owner_id", UUID.class),
                Journey.Kind.valueOf(row.getString("kind")), Journey.Status.valueOf(row.getString("status")),
                row.getTimestamp("started_at").toInstant(), completed == null ? null : completed.toInstant());
    }
}
