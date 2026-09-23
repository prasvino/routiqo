package com.routiqo.core.publiclive.privacy;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** PostgreSQL owns the cross-replica reservation; no process-local truth. */
public final class JdbcPilotPersonClaimStore implements PilotPersonClaimStore {
    private static final UUID NIL = new UUID(0, 0);
    private final JdbcTemplate jdbc;

    public JdbcPilotPersonClaimStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override public Outcome claim(UUID pilotId, UUID personRef, UUID requestId,
            String publicKey) {
        transaction();
        if (invalid(pilotId) || invalid(personRef) || invalid(requestId)
                || publicKey == null || !publicKey.matches("[a-zA-Z0-9:._-]{1,128}"))
            throw new IllegalArgumentException("Invalid pilot claim");
        Claim prior = find(pilotId, personRef);
        if (prior != null) return replay(prior, requestId, publicKey);
        int inserted = jdbc.update("""
            INSERT INTO public_live_person_claim(pilot_id, person_ref, claim_request_id,
                public_key, claimed_at)
            SELECT pilot_id, ?, ?, ?, clock_timestamp()
            FROM public_live_pilot
            WHERE pilot_id = ? AND starts_at <= clock_timestamp()
              AND clock_timestamp() < ends_at
            ON CONFLICT DO NOTHING
            """, personRef, requestId, publicKey, pilotId);
        if (inserted == 1) {
            // ON CONFLICT may have waited for another transaction past pilot close.
            // Roll back our new row if the fixed pilot is no longer open.
            Boolean stillOpen = jdbc.queryForObject("""
                SELECT starts_at <= clock_timestamp() AND clock_timestamp() < ends_at
                FROM public_live_pilot WHERE pilot_id = ?
                """, Boolean.class, pilotId);
            if (!Boolean.TRUE.equals(stillOpen)) throw new PilotPersonClaimConflict();
            return Outcome.NEW;
        }
        prior = find(pilotId, personRef);
        if (prior == null) throw new PilotPersonClaimConflict();
        return replay(prior, requestId, publicKey);
    }

    @Override public int deleteExpired() {
        transaction();
        return jdbc.update("""
            DELETE FROM public_live_person_claim WHERE (pilot_id, person_ref) IN (
                SELECT c.pilot_id, c.person_ref
                FROM public_live_person_claim c
                JOIN public_live_pilot p ON p.pilot_id = c.pilot_id
                WHERE p.retain_until <= clock_timestamp()
                ORDER BY p.retain_until, c.pilot_id, c.person_ref
                LIMIT 100 FOR UPDATE OF c SKIP LOCKED)
            """);
    }

    private Claim find(UUID pilotId, UUID personRef) {
        List<Claim> rows = jdbc.query("""
            SELECT c.claim_request_id, c.public_key
            FROM public_live_person_claim c
            JOIN public_live_pilot p ON p.pilot_id = c.pilot_id
            WHERE c.pilot_id = ? AND c.person_ref = ?
              AND p.retain_until > clock_timestamp()
            FOR UPDATE OF c
            """, (row, index) -> new Claim(row.getObject("claim_request_id", UUID.class),
                row.getString("public_key")), pilotId, personRef);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static Outcome replay(Claim prior, UUID requestId, String publicKey) {
        if (!requestId.equals(prior.requestId())
                || !publicKey.equals(prior.publicKey()))
            throw new PilotPersonClaimConflict();
        return Outcome.REPLAY;
    }

    private static boolean invalid(UUID id) { return id == null || NIL.equals(id); }
    private static void transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Pilot claim transaction required");
    }
    private record Claim(UUID requestId, String publicKey) {
        @Override public String toString() { return "PilotPersonClaim[private]"; }
    }
}
