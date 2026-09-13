package com.routiqo.core.privacy.infrastructure;

import com.routiqo.core.journey.application.JourneyCompletionParticipant;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.privacy.application.PresenceConsentConflict;
import com.routiqo.core.privacy.application.PresenceConsentParticipant;
import com.routiqo.core.privacy.domain.PresenceConsent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JdbcPresenceConsentParticipant
        implements PresenceConsentParticipant, JourneyCompletionParticipant {
    private final JdbcTemplate jdbc;

    public JdbcPresenceConsentParticipant(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public PresenceConsent read(Journey journey) {
        requireTransaction();
        Objects.requireNonNull(journey);
        Stored stored = findLocked(journey.ownerId()).stream().findFirst().orElse(null);
        return stateFor(journey, stored);
    }

    @Override
    public PresenceConsent change(Journey journey, long expectedGeneration, boolean sharing) {
        requireTransaction();
        Objects.requireNonNull(journey);
        Stored existing = findLocked(journey.ownerId()).stream().findFirst().orElse(null);
        PresenceConsent current = stateFor(journey, existing);
        if (expectedGeneration < 0 || current.generation() != expectedGeneration) {
            throw conflict();
        }
        if (journey.status() == Journey.Status.COMPLETED) {
            if (sharing) {
                throw conflict();
            }
            return current;
        }

        PresenceConsent changed;
        try {
            changed = current.changeSharing(sharing);
        } catch (ArithmeticException | IllegalStateException invalid) {
            throw conflict();
        }
        if (existing != null && existing.journeyId().equals(journey.id()) && changed.equals(current)) {
            return current;
        }
        jdbc.update("""
            INSERT INTO presence_consent
                (actor_id, journey_id, generation, sharing, journey_active)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (actor_id) DO UPDATE SET
                journey_id = EXCLUDED.journey_id,
                generation = EXCLUDED.generation,
                sharing = EXCLUDED.sharing,
                journey_active = EXCLUDED.journey_active
            """, changed.actorId(), changed.journeyId(), changed.generation(),
                changed.sharing(), changed.journeyActive());
        return changed;
    }

    @Override
    public void onCompleted(Journey completedJourney) {
        requireTransaction();
        Objects.requireNonNull(completedJourney);
        if (completedJourney.status() != Journey.Status.COMPLETED) {
            throw new IllegalArgumentException("Completed journey is required");
        }
        Stored stored = findLocked(completedJourney.ownerId()).stream().findFirst().orElse(null);
        if (stored == null || !stored.journeyId().equals(completedJourney.id())) {
            return;
        }
        PresenceConsent current = stored.toDomain();
        PresenceConsent ended;
        try {
            ended = current.endJourney();
        } catch (ArithmeticException invalid) {
            throw conflict();
        }
        if (ended.equals(current)) {
            return;
        }
        int updated = jdbc.update("""
            UPDATE presence_consent
            SET generation = ?, sharing = FALSE, journey_active = FALSE
            WHERE actor_id = ? AND journey_id = ? AND generation = ?
            """, ended.generation(), ended.actorId(), ended.journeyId(), current.generation());
        if (updated != 1) {
            throw conflict();
        }
    }

    private PresenceConsent stateFor(Journey journey, Stored stored) {
        if (stored == null || !stored.journeyId().equals(journey.id())) {
            return journey.status() == Journey.Status.ACTIVE
                    ? PresenceConsent.initial(journey.ownerId(), journey.id())
                    : new PresenceConsent(journey.ownerId(), journey.id(), 0, false, false);
        }
        PresenceConsent current = stored.toDomain();
        if (journey.status() == Journey.Status.COMPLETED) {
            return new PresenceConsent(current.actorId(), current.journeyId(),
                    current.generation(), false, false);
        }
        return current;
    }

    private List<Stored> findLocked(java.util.UUID actorId) {
        return jdbc.query("""
            SELECT actor_id, journey_id, generation, sharing, journey_active
            FROM presence_consent WHERE actor_id = ? FOR UPDATE
            """, JdbcPresenceConsentParticipant::map, actorId);
    }

    private static Stored map(ResultSet row, int number) throws SQLException {
        return new Stored(
                row.getObject("actor_id", java.util.UUID.class),
                row.getObject("journey_id", java.util.UUID.class),
                row.getLong("generation"),
                row.getBoolean("sharing"),
                row.getBoolean("journey_active"));
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Presence consent transaction is required");
        }
    }

    private static PresenceConsentConflict conflict() {
        return new PresenceConsentConflict();
    }

    private record Stored(
            java.util.UUID actorId,
            java.util.UUID journeyId,
            long generation,
            boolean sharing,
            boolean journeyActive) {
        PresenceConsent toDomain() {
            return new PresenceConsent(actorId, journeyId, generation, sharing, journeyActive);
        }

        @Override
        public String toString() {
            return "StoredPresenceConsent[private]";
        }
    }

    @Override
    public String toString() {
        return "JdbcPresenceConsentParticipant[private]";
    }
}
