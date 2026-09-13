package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.journey.application.JourneyCompletionParticipant;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.routeupdate.application.RouteBindingAttemptParticipant;
import com.routiqo.core.routeupdate.application.RouteBindingConflict;
import com.routiqo.core.routeupdate.domain.RouteBindingAttempt;
import com.routiqo.core.routeupdate.domain.StoredLiveRouteContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JdbcRouteBindingAttemptParticipant
        implements RouteBindingAttemptParticipant, JourneyCompletionParticipant {
    private static final UUID NIL_ID = new UUID(0, 0);
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcRouteBindingAttemptParticipant(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public RouteBindingAttempt reserve(Journey journey, long consentGeneration,
            UUID catalogVersion, Optional<UUID> expectedContextId, UUID attemptId) {
        requireTransaction();
        if (journey == null || journey.status() != Journey.Status.ACTIVE
                || consentGeneration < 0 || invalid(catalogVersion) || invalid(attemptId)
                || expectedContextId == null || expectedContextId.filter(
                        JdbcRouteBindingAttemptParticipant::invalid).isPresent()) {
            throw conflict();
        }
        RouteBindingAttempt previous = findLocked(journey.ownerId()).stream()
                .findFirst().orElse(null);
        Instant issuedAt = now();
        RouteBindingAttempt attempt;
        try {
            attempt = new RouteBindingAttempt(attemptId, journey.ownerId(), journey.id(),
                    consentGeneration, catalogVersion, expectedContextId, issuedAt,
                    issuedAt.plusSeconds(90), RouteBindingAttempt.State.PENDING);
        } catch (DateTimeException | IllegalArgumentException invalid) {
            throw conflict();
        }
        if (previous == null) {
            jdbc.update("""
                INSERT INTO route_binding_attempt
                    (actor_id, attempt_id, journey_id, consent_generation, catalog_version,
                     expected_context_id, issued_at, deadline, state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """, attempt.actorId(), attempt.attemptId(), attempt.journeyId(),
                    attempt.consentGeneration(), attempt.catalogVersion(),
                    attempt.expectedContextId().orElse(null), Timestamp.from(attempt.issuedAt()),
                    Timestamp.from(attempt.deadline()));
        } else {
            int updated = jdbc.update("""
                UPDATE route_binding_attempt
                SET attempt_id = ?, journey_id = ?, consent_generation = ?, catalog_version = ?,
                    expected_context_id = ?, issued_at = ?, deadline = ?, state = 'PENDING'
                WHERE actor_id = ? AND attempt_id = ?
                """, attempt.attemptId(), attempt.journeyId(), attempt.consentGeneration(),
                    attempt.catalogVersion(), attempt.expectedContextId().orElse(null),
                    Timestamp.from(attempt.issuedAt()), Timestamp.from(attempt.deadline()),
                    attempt.actorId(), previous.attemptId());
            if (updated != 1) throw conflict();
        }
        return attempt;
    }

    @Override
    public RouteBindingAttempt consume(RouteBindingAttempt expected,
            Optional<StoredLiveRouteContext> currentContext, UUID resultCatalogVersion) {
        requireTransaction();
        if (expected == null || currentContext == null || invalid(resultCatalogVersion)) {
            throw conflict();
        }
        RouteBindingAttempt stored = findLocked(expected.actorId()).stream()
                .findFirst().orElseThrow(JdbcRouteBindingAttemptParticipant::conflict);
        Instant now = now();
        Optional<UUID> actualContext = currentContext.map(value -> value.context().contextId());
        if (!stored.equals(expected) || !stored.catalogVersion().equals(resultCatalogVersion)
                || !stored.isCurrentAt(now) || !actualContext.equals(stored.expectedContextId())
                || currentContext.filter(value -> !value.isCurrentAt(now)).isPresent()) {
            throw conflict();
        }
        int updated = jdbc.update("""
            UPDATE route_binding_attempt SET state = 'CONSUMED'
            WHERE actor_id = ? AND attempt_id = ? AND state = 'PENDING'
            """, stored.actorId(), stored.attemptId());
        if (updated != 1) throw conflict();
        return stored.consume();
    }

    @Override
    public void invalidatePending(UUID actorId) {
        requireTransaction();
        if (invalid(actorId)) throw conflict();
        jdbc.update("""
            UPDATE route_binding_attempt SET state = 'INVALIDATED'
            WHERE actor_id = ? AND state = 'PENDING'
            """, actorId);
    }

    @Override
    public void onCompleted(Journey completedJourney) {
        requireTransaction();
        if (completedJourney == null || completedJourney.status() != Journey.Status.COMPLETED) {
            throw new IllegalArgumentException("Completed journey is required");
        }
        jdbc.update("""
            UPDATE route_binding_attempt SET state = 'INVALIDATED'
            WHERE actor_id = ? AND journey_id = ? AND state = 'PENDING'
            """, completedJourney.ownerId(), completedJourney.id());
    }

    @Override public int completionOrder() {
        return JourneyCompletionParticipant.ROUTE_BINDING_ATTEMPT_ORDER;
    }

    private List<RouteBindingAttempt> findLocked(UUID actorId) {
        return jdbc.query("""
            SELECT actor_id, attempt_id, journey_id, consent_generation, catalog_version,
                   expected_context_id, issued_at, deadline, state
            FROM route_binding_attempt WHERE actor_id = ? FOR UPDATE
            """, JdbcRouteBindingAttemptParticipant::map, actorId);
    }

    private static RouteBindingAttempt map(ResultSet row, int number) throws SQLException {
        return new RouteBindingAttempt(row.getObject("attempt_id", UUID.class),
                row.getObject("actor_id", UUID.class), row.getObject("journey_id", UUID.class),
                row.getLong("consent_generation"), row.getObject("catalog_version", UUID.class),
                Optional.ofNullable(row.getObject("expected_context_id", UUID.class)),
                row.getTimestamp("issued_at").toInstant(), row.getTimestamp("deadline").toInstant(),
                RouteBindingAttempt.State.valueOf(row.getString("state")));
    }

    private Instant now() {
        try {
            return clock.instant().truncatedTo(ChronoUnit.MICROS);
        } catch (RuntimeException unavailable) {
            throw conflict();
        }
    }

    private static boolean invalid(UUID id) { return id == null || NIL_ID.equals(id); }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Route binding attempt transaction is required");
        }
    }

    private static RouteBindingConflict conflict() { return new RouteBindingConflict(); }

    @Override public String toString() { return "JdbcRouteBindingAttemptParticipant[private]"; }
}
