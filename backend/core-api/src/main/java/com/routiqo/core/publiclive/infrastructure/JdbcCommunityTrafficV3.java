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
import org.springframework.transaction.support.TransactionSynchronizationManager;
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

    /** Minimized owner receipt: proves only that this reporter's request was received. */
    public record Receipt(Instant receivedAt, Instant receiptExpiresAt) {}

    static final int REPORTS_PER_DAY = 10;
    private static final long REPORTER_ROW_SECONDS = 168L * 3600;
    private static final long GROUP_SECONDS = 720L * 3600;

    /**
     * Report protocol (ADR 0064). Lock order: reporter account, projection, report, group. An exact
     * retry is answered from the reporter's own retained row before any current authorization, so a
     * lost response stays recoverable after the moment expires; new reports need current visibility
     * and the durable rolling quota.
     */
    public Receipt report(UUID actor, UUID journey, UUID ref, UUID requestId, String reason) {
        if (!List.of("INACCURATE", "UNSAFE", "SPAM").contains(reason))
            throw new IllegalArgumentException("Invalid report reason");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Report intake must own its transaction");
        return transaction.execute(status -> {
            // The reporter's own account row serializes that reporter's quota. It is taken first,
            // matching the account-first order of every other account-bound write.
            if (jdbc.query("""
                    SELECT id FROM routiqo_account WHERE id = ? AND enabled FOR NO KEY UPDATE
                    """, (row, n) -> row.getObject(1, UUID.class), actor).isEmpty())
                throw new Missing();
            Instant now = clock.instant();
            List<Prior> prior = jdbc.query("""
                    SELECT ref, reason, created_at, expires_at FROM community_traffic_report_v3
                    WHERE actor_id = ? AND request_id = ? AND expires_at > ?
                    """, (row, n) -> new Prior(row.getObject(1, UUID.class), row.getString(2),
                            row.getTimestamp(3).toInstant(), row.getTimestamp(4).toInstant()),
                    actor, requestId, Timestamp.from(now));
            if (!prior.isEmpty()) {
                Prior earlier = prior.getFirst();
                if (earlier.ref().equals(ref) && earlier.reason().equals(reason))
                    return new Receipt(earlier.createdAt(), earlier.expiresAt());
                throw new Conflict();
            }
            if (!eligibleReader(actor, journey, now) || !visible(actor, journey, ref, now))
                throw new Missing();
            // The projection share lock serializes report creation with moderator review.
            // Recheck time after a possible wait so an expired projection cannot be reported.
            now = clock.instant();
            if (!visible(actor, journey, ref, now)) throw new Missing();
            if (Boolean.TRUE.equals(jdbc.query("""
                    SELECT TRUE FROM community_traffic_report_v3 WHERE actor_id = ? AND ref = ?
                    """, (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rows -> rows.next(),
                    actor, ref))) throw new Conflict();
            Integer recent = jdbc.queryForObject("""
                    SELECT count(*) FROM community_traffic_report_v3
                    WHERE actor_id = ? AND created_at > ?
                    """, Integer.class, actor, Timestamp.from(now.minusSeconds(24L * 3600)));
            if (recent != null && recent >= REPORTS_PER_DAY) throw new Limited();
            Instant expires = now.plusSeconds(REPORTER_ROW_SECONDS);
            int created = jdbc.update("""
                    INSERT INTO community_traffic_report_v3
                      (actor_id, request_id, ref, reason, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, actor, requestId, ref, reason, Timestamp.from(now), Timestamp.from(expires));
            if (created == 0) throw new Conflict();
            Long sequence = jdbc.queryForObject("""
                    SELECT review_sequence FROM community_traffic_report_v3
                    WHERE actor_id = ? AND request_id = ?
                    """, Long.class, actor, requestId);
            jdbc.update("""
                    INSERT INTO community_traffic_report_group_v3
                      (ref, latest, latest_sequence, inaccurate, unsafe, spam, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (ref) DO UPDATE SET
                      latest = GREATEST(community_traffic_report_group_v3.latest, EXCLUDED.latest),
                      latest_sequence = GREATEST(community_traffic_report_group_v3.latest_sequence, EXCLUDED.latest_sequence),
                      inaccurate = community_traffic_report_group_v3.inaccurate + EXCLUDED.inaccurate,
                      unsafe = community_traffic_report_group_v3.unsafe + EXCLUDED.unsafe,
                      spam = community_traffic_report_group_v3.spam + EXCLUDED.spam,
                      expires_at = GREATEST(community_traffic_report_group_v3.expires_at, EXCLUDED.expires_at)
                    """, ref, Timestamp.from(now), sequence,
                    reason.equals("INACCURATE") ? 1 : 0, reason.equals("UNSAFE") ? 1 : 0,
                    reason.equals("SPAM") ? 1 : 0, Timestamp.from(now.plusSeconds(GROUP_SECONDS)));
            return new Receipt(now, expires);
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
                FOR SHARE OF p
                """, (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rows -> rows.next(),
                actor, journey, Timestamp.from(now), catalog.version(),
                ref, Timestamp.from(now)));
    }

    private record Prior(UUID ref, String reason, Instant createdAt, Instant expiresAt) {}
    public static final class Missing extends RuntimeException {}
    public static final class Conflict extends RuntimeException {}
    /** The reporter reached the durable rolling report quota. */
    public static final class Limited extends RuntimeException {}
}
