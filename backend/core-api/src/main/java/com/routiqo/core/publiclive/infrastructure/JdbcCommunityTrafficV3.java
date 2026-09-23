package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Authenticated reader/report store and grant-gated internal moderation command. */
public final class JdbcCommunityTrafficV3 {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RouteAnchorCatalog catalog;
    private final Clock clock;
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneOffset.UTC);

    public JdbcCommunityTrafficV3(JdbcTemplate jdbc, PlatformTransactionManager manager,
            RouteAnchorCatalog catalog, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transaction.setTimeout(8);
        this.catalog = Objects.requireNonNull(catalog);
        this.clock = Objects.requireNonNull(clock);
    }

    public record Moment(UUID ref, String areaLabel, String trafficValue,
            String observationPeriod, Instant expiresAt, String source, int schemaVersion) {}

    public record Feed(int schemaVersion, Instant serverTime, List<Moment> moments) {}

    public Feed read(UUID actor, UUID journey) {
        Instant now = clock.instant();
        if (!eligibleReader(actor, journey, now)) throw new Missing();
        List<Moment> moments = jdbc.query("""
                SELECT p.ref, p.area_label, p.traffic_value, p.window_start, p.expires_at
                FROM community_traffic_projection_v3 p
                JOIN live_route_context x ON x.actor_id = ? AND x.journey_id = ?
                  AND x.expires_at > ? AND x.catalog_version = ?
                  AND p.anchor_id = ANY(x.anchor_ids)
                  AND p.catalog_version = x.catalog_version
                WHERE p.suppressed_at IS NULL AND p.expires_at > ?
                  AND p.schema_version = 3
                ORDER BY p.window_start DESC, p.ref LIMIT 20
                """, (row, number) -> {
                    Instant start = row.getTimestamp(4).toInstant();
                    return new Moment(row.getObject(1, UUID.class), row.getString(2),
                            row.getString(3).toLowerCase(java.util.Locale.ROOT),
                            PERIOD.format(start) + "–" + PERIOD.format(start.plusSeconds(300)) + " UTC",
                            row.getTimestamp(5).toInstant(), "community", 3);
                }, actor, journey, Timestamp.from(now), catalog.version(), Timestamp.from(now));
        Instant serverTime = clock.instant();
        if (!eligibleReader(actor, journey, serverTime)) throw new Missing();
        return new Feed(3, serverTime, moments.stream()
                .filter(moment -> moment.expiresAt().isAfter(serverTime)).toList());
    }

    public void report(UUID actor, UUID journey, UUID ref, UUID requestId, String reason) {
        if (!List.of("INACCURATE", "UNSAFE", "SPAM").contains(reason))
            throw new IllegalArgumentException("Invalid report reason");
        transaction.executeWithoutResult(status -> {
            Instant now = clock.instant();
            if (!eligibleReader(actor, journey, now) || !visible(actor, journey, ref, now))
                throw new Missing();
            List<Report> prior = jdbc.query("""
                    SELECT ref, reason FROM community_traffic_report_v3
                    WHERE actor_id = ? AND request_id = ?
                    """, (row, n) -> new Report(row.getObject(1, UUID.class), row.getString(2)),
                    actor, requestId);
            if (!prior.isEmpty()) {
                if (prior.getFirst().ref().equals(ref) && prior.getFirst().reason().equals(reason))
                    return;
                throw new Conflict();
            }
            int created = jdbc.update("""
                    INSERT INTO community_traffic_report_v3
                      (actor_id, request_id, ref, reason, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, actor, requestId, ref, reason, Timestamp.from(now),
                    Timestamp.from(now.plusSeconds(720L * 3600)));
            if (created == 0) throw new Conflict();
        });
    }

    /** Internal only. Caller must be authenticated by an external admin boundary. */
    public void suppress(UUID operator, UUID requestId, UUID ref, String reason) {
        if (!List.of("INACCURATE", "UNSAFE", "SPAM", "POLICY").contains(reason))
            throw new IllegalArgumentException("Invalid suppression reason");
        transaction.executeWithoutResult(status -> {
            Instant now = clock.instant();
            Boolean allowed = jdbc.query("""
                    SELECT TRUE FROM moderation_operator_grant g
                    JOIN routiqo_account a ON a.id = g.operator_id AND a.enabled
                    WHERE g.operator_id = ? AND g.permission = 'traffic_suppress'
                      AND g.issued_at <= ? AND g.expires_at > ? FOR UPDATE OF g
                    """, (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rows -> rows.next(),
                    operator, Timestamp.from(now), Timestamp.from(now));
            if (!Boolean.TRUE.equals(allowed)) throw new Missing();
            List<Audit> prior = jdbc.query("""
                    SELECT ref, reason FROM community_traffic_suppression_audit_v3
                    WHERE operator_id = ? AND request_id = ?
                    """, (row, n) -> new Audit(row.getObject(1, UUID.class), row.getString(2)),
                    operator, requestId);
            if (!prior.isEmpty()) {
                if (prior.getFirst().ref().equals(ref) && prior.getFirst().reason().equals(reason))
                    return;
                throw new Conflict();
            }
            if (jdbc.update("""
                    UPDATE community_traffic_projection_v3 SET suppressed_at = ?
                    WHERE ref = ? AND suppressed_at IS NULL AND expires_at > ?
                    """, Timestamp.from(now), ref, Timestamp.from(now)) != 1) throw new Missing();
            jdbc.update("""
                    INSERT INTO community_traffic_suppression_audit_v3
                      (operator_id, request_id, ref, reason, occurred_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, operator, requestId, ref, reason, Timestamp.from(now),
                    Timestamp.from(now.plusSeconds(720L * 3600)));
        });
    }

    private boolean eligibleReader(UUID actor, UUID journey, Instant now) {
        return Boolean.TRUE.equals(jdbc.query("""
                SELECT TRUE FROM journey j
                JOIN routiqo_account a ON a.id = j.owner_id AND a.enabled
                JOIN live_route_context x ON x.actor_id = a.id AND x.journey_id = j.id
                  AND x.expires_at > ? AND x.catalog_version = ?
                JOIN presence_consent c ON c.actor_id = a.id AND c.journey_id = j.id
                  AND c.sharing AND c.journey_active
                WHERE j.id = ? AND j.owner_id = ? AND j.status = 'ACTIVE'
                """, (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rows -> rows.next(),
                Timestamp.from(now), catalog.version(), journey, actor));
    }

    private boolean visible(UUID actor, UUID journey, UUID ref, Instant now) {
        return Boolean.TRUE.equals(jdbc.query("""
                SELECT TRUE FROM community_traffic_projection_v3 p
                JOIN live_route_context x ON x.actor_id = ? AND x.journey_id = ?
                  AND x.expires_at > ? AND x.catalog_version = ?
                  AND p.anchor_id = ANY(x.anchor_ids)
                  AND p.catalog_version = x.catalog_version
                WHERE p.ref = ? AND p.suppressed_at IS NULL AND p.expires_at > ?
                """, (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rows -> rows.next(),
                actor, journey, Timestamp.from(now), catalog.version(),
                ref, Timestamp.from(now)));
    }

    private record Report(UUID ref, String reason) {}
    private record Audit(UUID ref, String reason) {}
    public static final class Missing extends RuntimeException {}
    public static final class Conflict extends RuntimeException {}
}
