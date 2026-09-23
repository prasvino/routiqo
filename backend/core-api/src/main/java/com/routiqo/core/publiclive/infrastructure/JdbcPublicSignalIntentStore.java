package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.publiclive.application.PublicSignalIntentConflict;
import com.routiqo.core.publiclive.application.PublicSignalIntentStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Private public-purpose intent ledger. Account/journey locking belongs to the caller. */
public final class JdbcPublicSignalIntentStore implements PublicSignalIntentStore {
    private final JdbcTemplate jdbc;

    public JdbcPublicSignalIntentStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override public Intent share(Intent requested) {
        transaction();
        Intent previous = find(requested.actorId(), requested.commandId());
        if (previous != null) return replay(previous, requested);
        int reserved = jdbc.update("""
            INSERT INTO public_person_window_slot(person_ref, anchor_id, category,
                window_start, expires_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """, requested.personRef(), requested.anchorId(), requested.category(),
                Timestamp.from(requested.windowStart()),
                Timestamp.from(requested.windowStart().plusSeconds(24 * 60 * 60)));
        if (reserved != 1) throw new PublicSignalIntentConflict();
        int inserted = jdbc.update("""
            INSERT INTO public_signal_intent(actor_id, command_id, journey_id,
                person_ref, verification_revision, restriction_revision,
                anchor_id, category, signal_value,
                window_start, received_at, evidence_expires_at, shared_at, share_request_id, state)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
            ON CONFLICT DO NOTHING
            """, requested.actorId(), requested.commandId(), requested.journeyId(),
                requested.personRef(), requested.verificationRevision(),
                requested.restrictionRevision(), requested.anchorId(),
                requested.category(), requested.value(), Timestamp.from(requested.windowStart()),
                Timestamp.from(requested.receivedAt()),
                Timestamp.from(requested.evidenceExpiresAt()), Timestamp.from(requested.sharedAt()),
                requested.shareRequestId());
        if (inserted != 1) {
            previous = find(requested.actorId(), requested.commandId());
            if (previous != null) return replay(previous, requested);
            throw new PublicSignalIntentConflict();
        }
        return requested;
    }

    @Override public State stop(UUID actorId, UUID journeyId, UUID commandId, Instant now) {
        transaction();
        List<State> rows = jdbc.query("""
            SELECT state FROM public_signal_intent
            WHERE actor_id = ? AND journey_id = ? AND command_id = ? FOR UPDATE
            """, (row, index) -> State.valueOf(row.getString("state")),
                actorId, journeyId, commandId);
        if (rows.isEmpty()) throw new SecurityException("Public share unavailable");
        if (rows.getFirst() == State.STOPPED) return State.STOPPED;
        int changed = jdbc.update("""
            UPDATE public_signal_intent SET state = 'STOPPED', stopped_at = ?
            WHERE actor_id = ? AND journey_id = ? AND command_id = ? AND state = 'ACTIVE'
            """, Timestamp.from(now), actorId, journeyId, commandId);
        if (changed != 1) throw new PublicSignalIntentConflict();
        return State.STOPPED;
    }

    @Override public HandlePage list(UUID actorId, Instant since, Cursor cursor) {
        transaction();
        List<Handle> rows;
        if (cursor == null) {
            rows = jdbc.query("""
                SELECT journey_id, command_id, state, shared_at
                FROM public_signal_intent
                WHERE actor_id = ? AND shared_at > ? AND window_start > ?
                ORDER BY shared_at DESC, command_id DESC LIMIT 101
                """, (row, index) -> handle(row), actorId, Timestamp.from(since),
                    Timestamp.from(since));
        } else {
            rows = jdbc.query("""
                SELECT journey_id, command_id, state, shared_at
                FROM public_signal_intent
                WHERE actor_id = ? AND shared_at > ? AND window_start > ?
                  AND (shared_at, command_id) < (?, ?)
                ORDER BY shared_at DESC, command_id DESC LIMIT 101
                """, (row, index) -> handle(row), actorId, Timestamp.from(since),
                    Timestamp.from(since),
                    Timestamp.from(cursor.sharedAt()), cursor.commandId());
        }
        if (rows.size() <= 100) return new HandlePage(rows, null);
        Handle last = rows.get(99);
        return new HandlePage(rows.subList(0, 100),
                new Cursor(last.sharedAt(), last.commandId()));
    }

    private static Handle handle(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Handle(row.getObject("journey_id", UUID.class),
                row.getObject("command_id", UUID.class),
                State.valueOf(row.getString("state")), row.getTimestamp("shared_at").toInstant());
    }

    private Intent find(UUID actorId, UUID commandId) {
        List<Intent> rows = jdbc.query("""
            SELECT actor_id, command_id, journey_id, person_ref, verification_revision,
                   restriction_revision,
                   anchor_id, category, signal_value, window_start, received_at,
                   evidence_expires_at, shared_at, share_request_id, state
            FROM public_signal_intent WHERE actor_id = ? AND command_id = ? FOR UPDATE
            """, (row, index) -> new Intent(
                row.getObject("actor_id", UUID.class), row.getObject("command_id", UUID.class),
                row.getObject("journey_id", UUID.class), row.getObject("person_ref", UUID.class),
                row.getLong("verification_revision"), row.getLong("restriction_revision"),
                row.getObject("anchor_id", UUID.class),
                row.getString("category"), row.getString("signal_value"),
                row.getTimestamp("window_start").toInstant(),
                row.getTimestamp("received_at").toInstant(),
                row.getTimestamp("evidence_expires_at").toInstant(),
                row.getTimestamp("shared_at").toInstant(),
                row.getObject("share_request_id", UUID.class),
                State.valueOf(row.getString("state"))), actorId, commandId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static Intent replay(Intent prior, Intent requested) {
        if (prior.state() != State.ACTIVE
                || !prior.actorId().equals(requested.actorId())
                || !prior.commandId().equals(requested.commandId())
                || !prior.journeyId().equals(requested.journeyId())
                || !prior.personRef().equals(requested.personRef())
                || prior.verificationRevision() != requested.verificationRevision()
                || prior.restrictionRevision() != requested.restrictionRevision()
                || !prior.anchorId().equals(requested.anchorId())
                || !prior.category().equals(requested.category())
                || !prior.value().equals(requested.value())
                || !prior.windowStart().equals(requested.windowStart())
                || !prior.receivedAt().equals(requested.receivedAt())
                || !prior.evidenceExpiresAt().equals(requested.evidenceExpiresAt())
                || !prior.shareRequestId().equals(requested.shareRequestId()))
            throw new PublicSignalIntentConflict();
        return prior;
    }

    private static void transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Public intent authority transaction required");
    }

    @Override public String toString() { return "JdbcPublicSignalIntentStore[private]"; }
}
