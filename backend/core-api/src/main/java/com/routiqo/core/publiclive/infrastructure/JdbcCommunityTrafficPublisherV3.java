package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.RouteAnchor;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;

/** Canonical V3 publisher. A session advisory lock is acquired before the RR input transaction. */
public final class JdbcCommunityTrafficPublisherV3 {
    private static final long LOCK = 0x4354524146464943L;
    private static final int MAX_BATCH = 100;
    private final DataSource dataSource;
    private final RouteAnchorCatalog catalog;
    private final Clock testClock;
    private final Runnable beforeSnapshot;
    private final Runnable afterSnapshot;

    public JdbcCommunityTrafficPublisherV3(DataSource dataSource, RouteAnchorCatalog catalog) {
        this(dataSource, catalog, null, () -> {}, () -> {});
    }

    /** Fixed time is only for deterministic database tests; production uses PostgreSQL time. */
    JdbcCommunityTrafficPublisherV3(DataSource dataSource, RouteAnchorCatalog catalog,
            Clock clock) {
        this(dataSource, catalog, clock, () -> {}, () -> {});
    }

    /** Test barrier for crossing an authority commit/expiry before the actual RR snapshot. */
    JdbcCommunityTrafficPublisherV3(DataSource dataSource, RouteAnchorCatalog catalog,
            Clock testClock, Runnable beforeSnapshot) {
        this(dataSource, catalog, testClock, beforeSnapshot, () -> {});
    }

    JdbcCommunityTrafficPublisherV3(DataSource dataSource, RouteAnchorCatalog catalog,
            Clock testClock, Runnable beforeSnapshot, Runnable afterSnapshot) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.catalog = Objects.requireNonNull(catalog);
        this.testClock = testClock;
        this.beforeSnapshot = Objects.requireNonNull(beforeSnapshot);
        this.afterSnapshot = Objects.requireNonNull(afterSnapshot);
    }

    /** Returns committed terminal decisions, or zero if another replica owns publication. */
    public int publish(int limit) {
        if (limit < 1 || limit > MAX_BATCH) throw new IllegalArgumentException("Invalid batch");
        try (Connection connection = dataSource.getConnection()) {
            // This runs in autocommit, before RR starts. It never waits for another publisher.
            if (!tryLock(connection)) return 0;
            try {
                beforeSnapshot.run();
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setAutoCommit(false);
                try {
                    int count = publishSnapshot(connection, limit);
                    connection.commit();
                    return count;
                } catch (Exception failure) {
                    connection.rollback();
                    throw failure;
                } finally {
                    connection.setAutoCommit(true);
                }
            } finally {
                unlock(connection);
            }
        } catch (SQLException failure) {
            throw new DataAccessResourceFailureException("Community traffic publication unavailable", failure);
        }
    }

    private boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, LOCK);
            statement.setQueryTimeout(3);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private void unlock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, LOCK);
            statement.setQueryTimeout(3);
            statement.execute();
        }
    }

    private int publishSnapshot(Connection connection, int limit) throws SQLException {
        // First RR statement: establish the actual input snapshot, then take DB time within it.
        // This happens only after advisory ownership; lock acquisition cannot pin a stale snapshot.
        Instant now;
        try (PreparedStatement statement = connection.prepareStatement("SELECT clock_timestamp()")) {
            statement.setQueryTimeout(3);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Missing evaluation time");
                now = row.getTimestamp(1).toInstant();
            }
        }
        afterSnapshot.run();
        if (testClock != null) now = testClock.instant();
        List<Window> windows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.anchor_id, w.window_start
                FROM unnest(?::uuid[]) a(anchor_id)
                CROSS JOIN (VALUES (?::timestamptz), (?::timestamptz)) w(window_start)
                WHERE w.window_start + INTERVAL '5 minutes' <= ?
                  AND w.window_start + INTERVAL '15 minutes' > ?
                  AND NOT EXISTS (SELECT 1 FROM community_traffic_decision_v3 d
                    WHERE d.schema_version = 3 AND d.catalog_version = ?
                      AND d.anchor_id = a.anchor_id AND d.window_start = w.window_start)
                ORDER BY w.window_start, a.anchor_id LIMIT ?
                """)) {
            UUID[] anchors = catalog.anchors().stream().map(RouteAnchor::anchorId).toArray(UUID[]::new);
            statement.setArray(1, connection.createArrayOf("uuid", anchors));
            Instant floor = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), 300) * 300);
            statement.setTimestamp(2, Timestamp.from(floor.minusSeconds(300)));
            statement.setTimestamp(3, Timestamp.from(floor.minusSeconds(600)));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setTimestamp(5, Timestamp.from(now));
            statement.setObject(6, catalog.version());
            statement.setInt(7, limit);
            statement.setQueryTimeout(8);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) windows.add(new Window(rows.getObject(1, UUID.class),
                        rows.getTimestamp(2).toInstant()));
            }
        }
        int inserted = 0;
        for (Window window : windows) {
            String winner = winner(connection, window, now);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO community_traffic_decision_v3
                      (schema_version, catalog_version, anchor_id, window_start, outcome, traffic_value,
                       decided_at, expires_at)
                    VALUES (3, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """)) {
                statement.setObject(1, catalog.version());
                statement.setObject(2, window.anchor());
                statement.setTimestamp(3, Timestamp.from(window.start()));
                statement.setString(4, winner == null ? "NO_OUTPUT" : "PUBLISHED");
                statement.setString(5, winner);
                statement.setTimestamp(6, Timestamp.from(now));
                statement.setTimestamp(7, Timestamp.from(window.start().plusSeconds(900)));
                statement.setQueryTimeout(8);
                int result = statement.executeUpdate();
                if (result == 0) continue;
            }
            if (winner != null) projection(connection, window, winner);
            inserted++;
        }
        return inserted;
    }

    private String winner(Connection connection, Window window, Instant now) throws SQLException {
        RouteAnchor anchor = catalog.anchors().stream()
                .filter(a -> a.anchorId().equals(window.anchor())
                        && a.categories().contains(QuickSignalValue.Category.TRAFFIC)
                        && a.displayLabel().isPresent()).findFirst().orElse(null);
        if (anchor == null) return null;
        List<ValueCount> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT c.traffic_value, count(DISTINCT c.actor_id)
                FROM community_traffic_candidate_v3 c
                JOIN routiqo_account a ON a.id = c.actor_id AND a.enabled
                JOIN journey j ON j.id = c.journey_id AND j.owner_id = c.actor_id
                  AND j.status = 'ACTIVE'
                JOIN presence_consent p ON p.actor_id = c.actor_id
                  AND p.journey_id = c.journey_id AND p.sharing AND p.journey_active
                  AND p.generation = c.consent_generation
                JOIN quick_signal_receipt r ON r.actor_id = c.actor_id
                  AND r.command_id = c.command_id AND r.journey_id = c.journey_id
                  AND r.anchor_id = c.anchor_id AND r.signal_value = c.traffic_value
                  AND r.state = 'ACTIVE' AND r.evidence_expires_at > ?
                  AND r.consent_generation = p.generation
                JOIN signal_command_grant g ON g.actor_id = c.actor_id
                  AND g.command_id = c.command_id AND g.journey_id = c.journey_id
                  AND g.state = 'CONSUMED'
                JOIN live_route_context x ON x.actor_id = c.actor_id
                  AND x.journey_id = c.journey_id AND x.context_id = r.context_id
                  AND x.revision = r.route_revision AND x.expires_at > ?
                  AND x.catalog_version = c.catalog_version
                  AND c.anchor_id = ANY(x.anchor_ids)
                JOIN live_verified_contributor v ON v.account_id = c.actor_id
                  AND v.state = 'active' AND v.expires_at > ?
                LEFT JOIN live_contribution_restriction cr ON cr.actor_id = c.actor_id
                WHERE c.anchor_id = ? AND c.window_start = ? AND c.state = 'ACTIVE'
                  AND c.expires_at > ? AND c.catalog_version = ?
                  AND COALESCE(cr.restricted, FALSE) = FALSE
                  AND COALESCE(cr.revision, 0) = g.restriction_revision
                GROUP BY c.traffic_value
                """)) {
            for (int i = 1; i <= 3; i++) statement.setTimestamp(i, Timestamp.from(now));
            statement.setObject(4, window.anchor());
            statement.setTimestamp(5, Timestamp.from(window.start()));
            statement.setTimestamp(6, Timestamp.from(now));
            statement.setObject(7, catalog.version());
            statement.setQueryTimeout(8);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) values.add(new ValueCount(rows.getString(1), rows.getLong(2)));
            }
        }
        long total = values.stream().mapToLong(ValueCount::count).sum();
        if (total < 12) return null;
        values.sort(Comparator.comparingLong(ValueCount::count).reversed());
        ValueCount first = values.getFirst();
        if (first.count() < 10 || first.count() * 5 < total * 4
                || values.size() > 1 && values.get(1).count() == first.count()) return null;
        return first.value();
    }

    private void projection(Connection connection, Window window, String value) throws SQLException {
        String label = catalog.anchors().stream().filter(a -> a.anchorId().equals(window.anchor()))
                .findFirst().flatMap(RouteAnchor::displayLabel).orElseThrow();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO community_traffic_projection_v3
                  (ref, schema_version, catalog_version, anchor_id, window_start,
                   area_label, traffic_value, expires_at)
                VALUES (?, 3, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, catalog.version());
            statement.setObject(3, window.anchor());
            statement.setTimestamp(4, Timestamp.from(window.start()));
            statement.setString(5, label);
            statement.setString(6, value);
            statement.setTimestamp(7, Timestamp.from(window.start().plusSeconds(900)));
            statement.setQueryTimeout(8);
            statement.executeUpdate();
        }
    }

    private record Window(UUID anchor, Instant start) {}
    private record ValueCount(String value, long count) {}
}
