package com.routiqo.core.spot.infrastructure;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bounded physical expiry for Spot contributions (ADR 0036 pattern). Readers already hide expired
 * items; this keeps tables small, turns well-confirmed place tips into highlights, and removes
 * aliases once their room has no posts. Each step is its own short transaction.
 */
final class JdbcSpotContributionMaintenance {
    static final Duration ITEM_GRACE = Duration.ofHours(24);
    static final Duration KEY_RETENTION = Duration.ofHours(48);
    static final Duration LEDGER_RETENTION = Duration.ofHours(25);
    static final Duration VOTE_RETENTION = Duration.ofHours(37);
    static final Duration HIGHLIGHT_LIFE = Duration.ofDays(30);
    private static final ZoneId ROOM_ZONE = ZoneId.of("Asia/Kolkata");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    JdbcSpotContributionMaintenance(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setTimeout(5);
        this.clock = clock;
    }

    private static void limit(int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("Invalid maintenance limit");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Maintenance runs in its own transactions");
    }

    /**
     * Expired place posts with at least two "Still true" votes become highlights (at most 3 kept per
     * Spot, by votes then recency, for 30 days). Traffic posts and signals never become highlights.
     */
    int promoteHighlights(int limit) {
        limit(limit);
        return transactions.execute(status -> {
            Timestamp now = Timestamp.from(clock.instant());
            List<UUID> spots = jdbc.queryForList("""
                    WITH candidates AS (
                        SELECT p.ref, p.spot_id, p.actor_id, p.text, p.expires_at,
                               (SELECT count(*) FROM spot_vote v
                                WHERE v.item_ref = p.ref AND v.kind = 'STILL_TRUE') AS still_true
                        FROM spot_post p
                        WHERE p.type = 'place' AND p.state = 'ACTIVE' AND p.expires_at <= ?
                          AND NOT EXISTS (SELECT 1 FROM spot_highlight h WHERE h.source_post_ref = p.ref)
                          AND (SELECT count(*) FROM spot_vote v
                               WHERE v.item_ref = p.ref AND v.kind = 'STILL_TRUE') >= 2
                        ORDER BY p.expires_at LIMIT ? FOR UPDATE OF p SKIP LOCKED)
                    INSERT INTO spot_highlight (ref, spot_id, source_post_ref, actor_id, text, still_true,
                        created_at, expires_at)
                    SELECT gen_random_uuid(), spot_id, ref, actor_id, text, still_true, expires_at,
                           expires_at + INTERVAL '30 days'
                    FROM candidates
                    ON CONFLICT (source_post_ref) DO NOTHING
                    RETURNING spot_id
                    """, UUID.class, now, limit);
            for (UUID spot : spots.stream().distinct().toList()) {
                jdbc.update("""
                        DELETE FROM spot_highlight WHERE spot_id = ? AND ref NOT IN (
                            SELECT ref FROM spot_highlight WHERE spot_id = ?
                            ORDER BY still_true DESC, created_at DESC LIMIT 3)
                        """, spot, spot);
            }
            return spots.size();
        });
    }

    /** Removes posts and signals a day after they expired or ended, with their votes. */
    int purgeItems(int limit) {
        limit(limit);
        return transactions.execute(status -> {
            Timestamp cutoff = Timestamp.from(clock.instant().minus(ITEM_GRACE));
            List<UUID> posts = jdbc.queryForList("""
                    DELETE FROM spot_post WHERE ref IN (
                        SELECT ref FROM spot_post
                        WHERE expires_at <= ? OR (ended_at IS NOT NULL AND ended_at <= ?)
                        ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED)
                    RETURNING ref
                    """, UUID.class, cutoff, cutoff, limit);
            List<UUID> signals = jdbc.queryForList("""
                    DELETE FROM spot_signal WHERE ref IN (
                        SELECT ref FROM spot_signal
                        WHERE expires_at <= ? OR (ended_at IS NOT NULL AND ended_at <= ?)
                        ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED)
                    RETURNING ref
                    """, UUID.class, cutoff, cutoff, limit);
            if (!posts.isEmpty())
                jdbc.update("DELETE FROM spot_vote WHERE item_ref = ANY (?)", (Object) posts.toArray(UUID[]::new));
            // A key must never outlive its item: a late replay is then judged afresh and refused as
            // too old (410) instead of pointing at a missing row.
            var purged = new java.util.ArrayList<UUID>(posts);
            purged.addAll(signals);
            if (!purged.isEmpty())
                jdbc.update("DELETE FROM spot_contribution_key WHERE ref = ANY (?)", (Object) purged.toArray(UUID[]::new));
            return posts.size() + signals.size();
        });
    }

    /** Old ledger, idempotency keys, summary votes, expired highlights and aliases of empty rooms. */
    int purgeBookkeeping(int limit) {
        limit(limit);
        return transactions.execute(status -> {
            Instant now = clock.instant();
            int removed = 0;
            removed += jdbc.update("""
                    DELETE FROM spot_contribution_ledger WHERE id IN (
                        SELECT id FROM spot_contribution_ledger WHERE charged_at <= ?
                        ORDER BY charged_at LIMIT ? FOR UPDATE SKIP LOCKED)
                    """, Timestamp.from(now.minus(LEDGER_RETENTION)), limit);
            removed += jdbc.update("""
                    DELETE FROM spot_contribution_key WHERE (actor_id, client_key) IN (
                        SELECT actor_id, client_key FROM spot_contribution_key WHERE created_at <= ?
                        ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED)
                    """, Timestamp.from(now.minus(KEY_RETENTION)), limit);
            // Summary votes outlive no signal: every signal's maximum life is at most 36 hours.
            removed += jdbc.update("""
                    DELETE FROM spot_vote WHERE (item_ref, actor_id) IN (
                        SELECT item_ref, actor_id FROM spot_vote WHERE voted_at <= ?
                          AND NOT EXISTS (SELECT 1 FROM spot_post p WHERE p.ref = spot_vote.item_ref)
                        ORDER BY voted_at LIMIT ? FOR UPDATE SKIP LOCKED)
                    """, Timestamp.from(now.minus(VOTE_RETENTION)), limit);
            removed += jdbc.update("""
                    DELETE FROM spot_highlight WHERE ref IN (
                        SELECT ref FROM spot_highlight WHERE expires_at <= ?
                        ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED)
                    """, Timestamp.from(now), limit);
            LocalDate yesterday = LocalDate.ofInstant(now, ROOM_ZONE).minusDays(1);
            removed += jdbc.update("""
                    DELETE FROM spot_alias WHERE (spot_id, room_day, actor_id) IN (
                        SELECT a.spot_id, a.room_day, a.actor_id FROM spot_alias a
                        WHERE a.room_day < ? AND NOT EXISTS (
                            SELECT 1 FROM spot_post p WHERE p.spot_id = a.spot_id AND p.room_day = a.room_day)
                        ORDER BY a.room_day LIMIT ? FOR UPDATE SKIP LOCKED)
                    """, Date.valueOf(yesterday), limit);
            return removed;
        });
    }
}
