package com.routiqo.core.publiclive.infrastructure;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded physical retention; scheduling remains a separate operational gate. */
public final class JdbcPublicSignalIntentCleanup {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public JdbcPublicSignalIntentCleanup(JdbcTemplate jdbc,
            PlatformTransactionManager manager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transaction.setTimeout(5);
        this.clock = Objects.requireNonNull(clock);
    }

    public int deleteExpired() {
        Instant now = clock.instant();
        return transaction.execute(status -> {
            int decisions = jdbc.update("""
                DELETE FROM private_live_window_decision WHERE
                    (anchor_id, category, window_start) IN (
                    SELECT anchor_id, category, window_start
                    FROM private_live_window_decision WHERE expires_at <= ?
                    ORDER BY expires_at, anchor_id, category, window_start
                    LIMIT 100 FOR UPDATE SKIP LOCKED)
                """, Timestamp.from(now));
            int intents = jdbc.update("""
                DELETE FROM public_signal_intent WHERE (actor_id, command_id) IN (
                    SELECT actor_id, command_id FROM public_signal_intent
                    WHERE window_start <= ?
                    ORDER BY window_start, actor_id, command_id
                    LIMIT 100 FOR UPDATE SKIP LOCKED)
                """, Timestamp.from(now.minusSeconds(24 * 60 * 60)));
            int slots = jdbc.update("""
                DELETE FROM public_person_window_slot WHERE
                    (person_ref, anchor_id, category, window_start) IN (
                    SELECT person_ref, anchor_id, category, window_start
                    FROM public_person_window_slot WHERE expires_at <= ?
                    ORDER BY expires_at, person_ref, anchor_id, category, window_start
                    LIMIT 100 FOR UPDATE SKIP LOCKED)
                """, Timestamp.from(now));
            return decisions + intents + slots;
        });
    }
}
