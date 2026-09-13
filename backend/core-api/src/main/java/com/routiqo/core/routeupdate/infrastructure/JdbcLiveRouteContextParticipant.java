package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.journey.application.JourneyCompletionParticipant;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.routeupdate.application.LiveRouteContextConflict;
import com.routiqo.core.routeupdate.application.LiveRouteContextParticipant;
import com.routiqo.core.routeupdate.domain.LiveRouteContext;
import com.routiqo.core.routeupdate.domain.StoredLiveRouteContext;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JdbcLiveRouteContextParticipant
        implements LiveRouteContextParticipant, JourneyCompletionParticipant {
    private static final UUID NIL_ID = new UUID(0, 0);
    private static final Duration MAX_LIFETIME = Duration.ofHours(24);
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcLiveRouteContextParticipant(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Optional<StoredLiveRouteContext> read(Journey journey) {
        requireTransaction();
        if (journey == null) {
            throw conflict();
        }
        StoredLiveRouteContext stored = findLocked(journey.ownerId()).stream()
                .findFirst().orElse(null);
        Instant now = currentTime();
        if (journey.status() != Journey.Status.ACTIVE || stored == null
                || !stored.context().journeyId().equals(journey.id())
                || !stored.isCurrentAt(now)) {
            return Optional.empty();
        }
        return Optional.of(stored);
    }

    @Override
    public StoredLiveRouteContext replace(Journey journey, Set<UUID> anchorIds, Duration lifetime,
            Optional<UUID> expectedCurrentContextId, UUID newContextId) {
        requireTransaction();
        if (journey == null || anchorIds == null || lifetime == null
                || expectedCurrentContextId == null || invalidId(newContextId)
                || expectedCurrentContextId.filter(JdbcLiveRouteContextParticipant::invalidId)
                        .isPresent()) {
            throw conflict();
        }

        StoredLiveRouteContext stored = findLocked(journey.ownerId()).stream()
                .findFirst().orElse(null);
        Instant now = currentTime();
        if (journey.status() != Journey.Status.ACTIVE
                || stored != null && now.isBefore(stored.issuedAt())) {
            throw conflict();
        }

        boolean sameJourney = stored != null
                && stored.context().journeyId().equals(journey.id());
        boolean current = sameJourney && stored.isCurrentAt(now);
        if (current) {
            if (expectedCurrentContextId.isEmpty()
                    || !expectedCurrentContextId.get().equals(stored.context().contextId())) {
                throw conflict();
            }
        } else if (expectedCurrentContextId.isPresent()) {
            throw conflict();
        }

        long revision;
        try {
            revision = sameJourney ? Math.incrementExact(stored.context().revision()) : 0;
        } catch (ArithmeticException overflow) {
            throw conflict();
        }

        StoredLiveRouteContext replacement;
        try {
            if (lifetime.isZero() || lifetime.isNegative()
                    || lifetime.compareTo(MAX_LIFETIME) > 0) {
                throw conflict();
            }
            Instant expiresAt = now.plus(lifetime).truncatedTo(ChronoUnit.MICROS);
            if (!expiresAt.isAfter(now)) {
                throw conflict();
            }
            LiveRouteContext context = new LiveRouteContext(newContextId, journey.ownerId(),
                    journey.id(), revision, anchorIds);
            replacement = new StoredLiveRouteContext(context, now, expiresAt);
        } catch (DateTimeException | IllegalArgumentException invalid) {
            throw conflict();
        }

        UUID[] anchors = replacement.context().anchorIds().toArray(UUID[]::new);
        if (stored == null) {
            jdbc.update("""
                INSERT INTO live_route_context
                    (actor_id, journey_id, context_id, revision, anchor_ids, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, replacement.context().actorId(), replacement.context().journeyId(),
                    replacement.context().contextId(), replacement.context().revision(), anchors,
                    timestamp(replacement.issuedAt()), timestamp(replacement.expiresAt()));
        } else {
            int updated = jdbc.update("""
                UPDATE live_route_context
                SET journey_id = ?, context_id = ?, revision = ?, anchor_ids = ?,
                    issued_at = ?, expires_at = ?
                WHERE actor_id = ? AND context_id = ?
                """, replacement.context().journeyId(), replacement.context().contextId(),
                    replacement.context().revision(), anchors, timestamp(replacement.issuedAt()),
                    timestamp(replacement.expiresAt()), replacement.context().actorId(),
                    stored.context().contextId());
            if (updated != 1) {
                throw conflict();
            }
        }
        return replacement;
    }

    @Override
    public void onCompleted(Journey completedJourney) {
        requireTransaction();
        if (completedJourney == null || completedJourney.status() != Journey.Status.COMPLETED) {
            throw new IllegalArgumentException("Completed journey is required");
        }
        jdbc.update("""
            DELETE FROM live_route_context
            WHERE actor_id = ? AND journey_id = ?
            """, completedJourney.ownerId(), completedJourney.id());
    }

    @Override
    public int completionOrder() {
        return JourneyCompletionParticipant.ROUTE_CONTEXT_ORDER;
    }

    private List<StoredLiveRouteContext> findLocked(UUID actorId) {
        return jdbc.query("""
            SELECT actor_id, journey_id, context_id, revision, anchor_ids, issued_at, expires_at
            FROM live_route_context WHERE actor_id = ? FOR UPDATE
            """, JdbcLiveRouteContextParticipant::map, actorId);
    }

    private static StoredLiveRouteContext map(ResultSet row, int number) throws SQLException {
        Array storedArray = row.getArray("anchor_ids");
        UUID[] anchors = (UUID[]) storedArray.getArray();
        Set<UUID> distinct = new LinkedHashSet<>(Arrays.asList(anchors));
        if (distinct.size() != anchors.length) {
            throw new SQLException("Stored live route context is invalid");
        }
        LiveRouteContext context = new LiveRouteContext(
                row.getObject("context_id", UUID.class),
                row.getObject("actor_id", UUID.class),
                row.getObject("journey_id", UUID.class),
                row.getLong("revision"), distinct);
        return new StoredLiveRouteContext(context,
                row.getTimestamp("issued_at").toInstant(),
                row.getTimestamp("expires_at").toInstant());
    }

    private Instant currentTime() {
        try {
            return clock.instant().truncatedTo(ChronoUnit.MICROS);
        } catch (RuntimeException unavailable) {
            throw conflict();
        }
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value.truncatedTo(ChronoUnit.MICROS));
    }

    private static boolean invalidId(UUID id) {
        return id == null || NIL_ID.equals(id);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Live route context transaction is required");
        }
    }

    private static LiveRouteContextConflict conflict() {
        return new LiveRouteContextConflict();
    }

    @Override
    public String toString() {
        return "JdbcLiveRouteContextParticipant[private]";
    }
}
