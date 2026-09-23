package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Disconnected internal candidate evaluator. Decisions are not a release authority and have no
 * public reader. Under READ COMMITTED, a concurrent authority change may commit after the
 * eligibility query, so even a CANDIDATE row cannot authorize delivery. A new privacy protocol
 * and a race-safe authority boundary are required before any delivery is wired.
 */
public final class JdbcPrivateLiveWindowPublisher {
    private static final int MAX_BATCH = 100;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RouteAnchorCatalog catalog;
    private final Clock clock;

    public JdbcPrivateLiveWindowPublisher(JdbcTemplate jdbc, PlatformTransactionManager manager,
            RouteAnchorCatalog catalog, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transaction.setTimeout(10);
        this.catalog = Objects.requireNonNull(catalog);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Evaluates at most {@code limit} closed, unexpired windows. Safe to retry across replicas. */
    public int evaluate(int limit) {
        if (limit < 1 || limit > MAX_BATCH) throw new IllegalArgumentException("Invalid window limit");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Window evaluation must own its transaction");
        return transaction.execute(status -> evaluateInTransaction(limit, clock.instant()));
    }

    private int evaluateInTransaction(int limit, Instant now) {
        List<Window> windows = jdbc.query("""
            SELECT DISTINCT i.anchor_id, i.category, i.window_start
            FROM public_signal_intent i
            WHERE i.window_start + INTERVAL '5 minutes' <= ?
              AND i.window_start + INTERVAL '15 minutes' > ?
              AND NOT EXISTS (SELECT 1 FROM private_live_window_decision d
                  WHERE d.anchor_id = i.anchor_id AND d.category = i.category
                    AND d.window_start = i.window_start)
            ORDER BY i.window_start, i.anchor_id, i.category
            LIMIT ?
            """, (row, number) -> new Window(row.getObject(1, UUID.class), row.getString(2),
                row.getTimestamp(3).toInstant()), Timestamp.from(now), Timestamp.from(now), limit);
        int recorded = 0;
        for (Window window : windows) {
            String selected = candidateValue(window, now);
            recorded += jdbc.update("""
                INSERT INTO private_live_window_decision(anchor_id, category, window_start,
                    decision, signal_value, decided_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, window.anchorId(), window.category(), Timestamp.from(window.start()),
                    selected == null ? "NO_RELEASE" : "CANDIDATE", selected,
                    Timestamp.from(now), Timestamp.from(window.start().plusSeconds(900)));
        }
        return recorded;
    }

    private String candidateValue(Window window, Instant now) {
        boolean curated;
        try {
            var category = QuickSignalValue.Category.valueOf(window.category());
            curated = catalog.anchors().stream().anyMatch(anchor ->
                anchor.anchorId().equals(window.anchorId()) && anchor.categories().contains(category));
        } catch (IllegalArgumentException invalid) {
            curated = false;
        }
        if (!curated) return null;

        // The joins fail closed when any authority is missing, stale, changed, or withdrawn.
        // This query only calculates an internal candidate; it must never back a public read.
        List<ValueCount> values = jdbc.query("""
            SELECT i.signal_value, count(*) AS contributors
            FROM public_signal_intent i
            JOIN routiqo_account a ON a.id = i.actor_id AND a.enabled
            JOIN journey j ON j.id = i.journey_id AND j.owner_id = i.actor_id
                AND j.status = 'ACTIVE'
            JOIN presence_consent c ON c.actor_id = i.actor_id
                AND c.journey_id = i.journey_id AND c.sharing AND c.journey_active
            JOIN quick_signal_receipt r ON r.actor_id = i.actor_id
                AND r.command_id = i.command_id AND r.journey_id = i.journey_id
                AND r.state = 'ACTIVE' AND r.evidence_expires_at > ?
                AND r.anchor_id = i.anchor_id AND r.category = i.category
                AND r.signal_value = i.signal_value AND r.received_at = i.received_at
                AND r.evidence_expires_at = i.evidence_expires_at
                AND r.consent_generation = c.generation
            JOIN signal_command_grant g ON g.actor_id = i.actor_id
                AND g.command_id = i.command_id AND g.journey_id = i.journey_id
                AND g.state = 'CONSUMED'
                AND g.restriction_revision = i.restriction_revision
            JOIN live_route_context x ON x.actor_id = i.actor_id
                AND x.journey_id = i.journey_id AND x.context_id = r.context_id
                AND x.revision = r.route_revision AND x.expires_at > ?
                AND x.catalog_version = ? AND i.anchor_id = ANY(x.anchor_ids)
            JOIN live_verified_contributor v ON v.account_id = i.actor_id
                AND v.person_ref = i.person_ref AND v.revision = i.verification_revision
                AND v.state = 'active' AND v.expires_at > ?
            LEFT JOIN live_contribution_restriction cr ON cr.actor_id = i.actor_id
            WHERE i.anchor_id = ? AND i.category = ? AND i.window_start = ?
              AND i.state = 'ACTIVE' AND i.evidence_expires_at > ?
              AND COALESCE(cr.restricted, FALSE) = FALSE
              AND COALESCE(cr.revision, 0) = i.restriction_revision
            GROUP BY i.signal_value
            """, (row, number) -> new ValueCount(row.getString(1), row.getLong(2)),
                Timestamp.from(now), Timestamp.from(now),
                catalog.version(), Timestamp.from(now), window.anchorId(), window.category(),
                Timestamp.from(window.start()), Timestamp.from(now));
        long total = values.stream().mapToLong(ValueCount::count).sum();
        if (total < 12) return null;
        long most = values.stream().mapToLong(ValueCount::count).max().orElse(0);
        if (most < 10 || values.stream().filter(value -> value.count() == most).count() != 1)
            return null;
        return values.stream().filter(value -> value.count() == most)
                .map(ValueCount::value).findFirst().orElse(null);
    }

    private record Window(UUID anchorId, String category, Instant start) { }
    private record ValueCount(String value, long count) { }
}
