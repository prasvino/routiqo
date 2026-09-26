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
    /** Rooms older than this (beyond yesterday) hold no readable post: the longest post life is under 3 days. */
    static final int HIDDEN_ALIAS_DAYS = 3;
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
     * Spot, by votes then recency, for 30 days). Traffic posts and signals never become highlights, and
     * neither does a hidden post or one with a report awaiting a decision. Dismissed or restored reports
     * (ruled not upheld) no longer block it (ADR 0075).
     */
    int promoteHighlights(int limit) {
        limit(limit);
        return transactions.execute(status -> {
            // One promoter at a time across replicas, so the top-3 trim sees every new highlight.
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended('spot-highlight-promotion', 0))",
                    Object.class);
            Timestamp now = Timestamp.from(clock.instant());
            // Each expired place post is considered exactly once, whatever its vote count.
            List<UUID> considered = jdbc.queryForList("""
                    UPDATE spot_post SET highlight_checked = TRUE WHERE ref IN (
                        SELECT ref FROM spot_post
                        WHERE type = 'place' AND state = 'ACTIVE' AND NOT highlight_checked AND expires_at <= ?
                        ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED)
                    RETURNING ref
                    """, UUID.class, now, limit);
            if (considered.isEmpty()) return 0;
            List<UUID> spots = jdbc.queryForList("""
                    INSERT INTO spot_highlight (ref, spot_id, source_post_ref, actor_id, text, still_true,
                        created_at, expires_at)
                    SELECT gen_random_uuid(), p.spot_id, p.ref, p.actor_id, p.text, votes.still_true, p.expires_at,
                           p.expires_at + INTERVAL '30 days'
                    FROM spot_post p
                    CROSS JOIN LATERAL (SELECT count(*)::INTEGER AS still_true FROM spot_vote v
                        WHERE v.item_ref = p.ref AND v.kind = 'STILL_TRUE') votes
                    WHERE p.ref = ANY (?) AND votes.still_true >= 2
                      AND p.moderation_hidden_at IS NULL
                      -- Reported posts wait for a decision; dismissed or restored ones may qualify (ADR 0075).
                      AND NOT EXISTS (SELECT 1 FROM spot_report_evidence e
                          WHERE e.ref = p.ref AND e.not_upheld_at IS NULL)
                    ON CONFLICT (source_post_ref) DO NOTHING
                    RETURNING spot_id
                    """, UUID.class, (Object) considered.toArray(UUID[]::new));
            for (UUID spot : spots.stream().distinct().toList()) {
                jdbc.update("""
                        DELETE FROM spot_highlight WHERE spot_id = ? AND ref NOT IN (
                            SELECT ref FROM spot_highlight WHERE spot_id = ?
                            ORDER BY still_true DESC, created_at DESC, ref LIMIT 3)
                        """, spot, spot);
            }
            return spots.size();
        });
    }

    /**
     * Removes posts and signals a day after they expired or ended, with their votes. A reported post,
     * or a signal current when its summary was reported, is kept as evidence for a fixed 30 days from
     * the first report naming it (ADR 0072).
     */
    int purgeItems(int limit) {
        limit(limit);
        return transactions.execute(status -> {
            Instant at = clock.instant();
            Timestamp now = Timestamp.from(at);
            Timestamp cutoff = Timestamp.from(at.minus(ITEM_GRACE));
            List<UUID> posts = jdbc.queryForList("""
                    DELETE FROM spot_post WHERE ref IN (
                        SELECT ref FROM spot_post p
                        WHERE (expires_at <= ? OR (ended_at IS NOT NULL AND ended_at <= ?))
                          AND NOT EXISTS (SELECT 1 FROM spot_report_evidence e
                              WHERE e.ref = p.ref AND e.expires_at > ?)
                        ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED)
                    RETURNING ref
                    """, UUID.class, cutoff, cutoff, now, limit);
            List<UUID> signals = jdbc.queryForList("""
                    DELETE FROM spot_signal WHERE ref IN (
                        SELECT ref FROM spot_signal s
                        WHERE (expires_at <= ? OR (ended_at IS NOT NULL AND ended_at <= ?))
                          AND NOT EXISTS (SELECT 1 FROM spot_report_evidence e
                              WHERE e.ref = s.ref AND e.expires_at > ?)
                        ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED)
                    RETURNING ref
                    """, UUID.class, cutoff, cutoff, now, limit);
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

    /**
     * Old ledger, idempotency keys, summary votes, expired highlights, expired report rows (7 days),
     * report groups and evidence (30 days), hidden aliases of old rooms, and aliases of empty rooms.
     */
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
            removed += jdbc.update("""
                    DELETE FROM spot_report WHERE (reporter_id, request_id) IN (
                        SELECT reporter_id, request_id FROM spot_report WHERE expires_at <= ?
                        ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED)
                    """, Timestamp.from(now), limit);
            removed += jdbc.update("""
                    DELETE FROM spot_report_group WHERE (item_ref, window_start) IN (
                        SELECT item_ref, window_start FROM spot_report_group WHERE expires_at <= ?
                        ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED)
                    """, Timestamp.from(now), limit);
            removed += jdbc.update("""
                    DELETE FROM spot_report_evidence WHERE ref IN (
                        SELECT ref FROM spot_report_evidence WHERE expires_at <= ?
                        ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED)
                    """, Timestamp.from(now), limit);
            // Moderation records (ADR 0075): 30-day decisions, read audit and reporter outcomes, and
            // 30-minute account references.
            for (String table : new String[] {"spot_moderation_action", "spot_moderation_read_audit",
                    "spot_reporter_not_upheld", "spot_account_lookup_ref"}) {
                removed += jdbc.update("""
                        DELETE FROM %1$s WHERE ctid IN (
                            SELECT ctid FROM %1$s WHERE expires_at <= ?
                            ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED)
                        """.formatted(table), Timestamp.from(now), limit);
            }
            LocalDate yesterday = LocalDate.ofInstant(now, ROOM_ZONE).minusDays(1);
            // A hidden alias only matters while its room's posts can still be read.
            removed += jdbc.update("""
                    DELETE FROM spot_hidden_alias WHERE (blocker_id, spot_id, room_day, alias) IN (
                        SELECT blocker_id, spot_id, room_day, alias FROM spot_hidden_alias WHERE room_day < ?
                        ORDER BY room_day LIMIT ? FOR UPDATE SKIP LOCKED)
                    """, Date.valueOf(yesterday.minusDays(HIDDEN_ALIAS_DAYS)), limit);
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
