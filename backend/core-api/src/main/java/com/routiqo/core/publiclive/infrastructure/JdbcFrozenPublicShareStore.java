package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.publiclive.application.FrozenPublicShareConflict;
import com.routiqo.core.publiclive.application.FrozenPublicShareStore;
import com.routiqo.core.publiclive.privacy.PilotPersonClaimConflict;
import com.routiqo.core.publiclive.privacy.PilotPersonClaimStore;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Separate from mutable V18 intent. Deliberately has no Spring bean or transport. */
public final class JdbcFrozenPublicShareStore implements FrozenPublicShareStore {
    private final JdbcTemplate jdbc;
    private final PilotPersonClaimStore claims;

    public JdbcFrozenPublicShareStore(JdbcTemplate jdbc, PilotPersonClaimStore claims) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.claims = Objects.requireNonNull(claims);
    }

    @Override public Manifest manifest(UUID pilotId) {
        transaction();
        List<Manifest> rows = jdbc.query("""
            SELECT m.catalog_version, m.anchor_ids, p.starts_at, p.ends_at
            FROM public_live_pilot_manifest m
            JOIN public_live_pilot p ON p.pilot_id = m.pilot_id
            WHERE m.pilot_id = ? FOR SHARE OF m
            """, (row, index) -> {
                UUID[] ids = (UUID[]) row.getArray("anchor_ids").getArray();
                Set<UUID> distinct = Set.copyOf(Arrays.asList(ids));
                if (distinct.size() != ids.length) throw new FrozenPublicShareConflict();
                return new Manifest(row.getObject("catalog_version", UUID.class), distinct,
                        row.getTimestamp("starts_at").toInstant(),
                        row.getTimestamp("ends_at").toInstant());
            }, pilotId);
        if (rows.size() != 1) throw new FrozenPublicShareConflict();
        return rows.getFirst();
    }

    @Override public Acknowledgement findOwner(UUID pilotId, UUID actorId,
            UUID journeyId, UUID commandId, UUID requestId) {
        transaction();
        List<Acknowledgement> rows = jdbc.query("""
            SELECT f.pilot_id, f.owner_actor_id, f.journey_id, f.command_id,
                   c.claim_request_id, c.public_key, c.claimed_at
            FROM public_live_frozen_share_v2 f
            JOIN public_live_person_claim c USING (pilot_id, person_ref)
            JOIN public_live_pilot p ON p.pilot_id = f.pilot_id
            WHERE f.pilot_id = ? AND f.owner_actor_id = ? AND f.command_id = ?
              AND p.retain_until > clock_timestamp()
            FOR UPDATE OF f
            """, (row, index) -> map(row), pilotId, actorId, commandId);
        if (rows.isEmpty()) return null;
        Acknowledgement prior = rows.getFirst();
        if (!prior.journeyId().equals(journeyId) || !prior.requestId().equals(requestId))
            throw new FrozenPublicShareConflict();
        return prior;
    }

    @Override public Acknowledgement freeze(UUID pilotId, UUID personRef, UUID actorId,
            UUID journeyId, UUID commandId, UUID requestId, String publicKey) {
        transaction();
        try {
            PilotPersonClaimStore.Outcome outcome = claims.claim(
                    pilotId, personRef, requestId, publicKey);
            if (outcome == PilotPersonClaimStore.Outcome.NEW) {
                jdbc.update("""
                    INSERT INTO public_live_frozen_share_v2
                        (pilot_id, person_ref, owner_actor_id, journey_id, command_id)
                    VALUES (?, ?, ?, ?, ?)
                    """, pilotId, personRef, actorId, journeyId, commandId);
            }
            Acknowledgement ack = findOwner(pilotId, actorId, journeyId, commandId, requestId);
            if (ack == null || !ack.publicKey().equals(publicKey))
                throw new FrozenPublicShareConflict();
            return ack;
        } catch (PilotPersonClaimConflict | DataIntegrityViolationException conflict) {
            throw new FrozenPublicShareConflict();
        }
    }

    private static Acknowledgement map(java.sql.ResultSet row) throws SQLException {
        return new Acknowledgement(row.getObject("pilot_id", UUID.class),
                row.getObject("owner_actor_id", UUID.class),
                row.getObject("journey_id", UUID.class),
                row.getObject("command_id", UUID.class),
                row.getObject("claim_request_id", UUID.class),
                row.getString("public_key"), row.getTimestamp("claimed_at").toInstant());
    }

    private static void transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Frozen Share transaction required");
    }
}
