package com.routiqo.core.moderation.infrastructure;

import com.routiqo.core.moderation.application.DirectionalBlockParticipant;
import com.routiqo.core.moderation.domain.DirectionalBlock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Latest-state storage; account-pair authority serializes both directions before entry. */
public final class JdbcDirectionalBlockParticipant implements DirectionalBlockParticipant {
    private final JdbcTemplate jdbc;

    public JdbcDirectionalBlockParticipant(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public DirectionalBlock read(UUID blockerId, UUID targetId) {
        requireTransaction();
        List<DirectionalBlock> rows = jdbc.query("""
            SELECT revision, blocked FROM live_block_edge
            WHERE blocker_id = ? AND target_id = ?
            """, (row, number) -> new DirectionalBlock(blockerId, targetId,
                    row.getLong("revision"), row.getBoolean("blocked")), blockerId, targetId);
        return rows.isEmpty() ? DirectionalBlock.initial(blockerId, targetId) : rows.getFirst();
    }

    @Override
    public int outgoingCount(UUID blockerId) {
        requireTransaction();
        return jdbc.queryForObject("""
            SELECT count(*) FROM live_block_edge WHERE blocker_id = ?
            """, Integer.class, blockerId);
    }

    @Override
    public void replace(DirectionalBlock prior, DirectionalBlock updated) {
        requireTransaction();
        if (!prior.blockerId().equals(updated.blockerId())
                || !prior.targetId().equals(updated.targetId())
                || prior.revision() == Long.MAX_VALUE
                || updated.revision() != prior.revision() + 1) throw changed();
        int changed;
        if (prior.revision() == 0) {
            changed = jdbc.update("""
                INSERT INTO live_block_edge(blocker_id, target_id, revision, blocked)
                VALUES (?, ?, ?, ?) ON CONFLICT (blocker_id, target_id) DO NOTHING
                """, updated.blockerId(), updated.targetId(), updated.revision(),
                    updated.blocked());
        } else {
            changed = jdbc.update("""
                UPDATE live_block_edge SET revision = ?, blocked = ?
                WHERE blocker_id = ? AND target_id = ? AND revision = ? AND blocked = ?
                """, updated.revision(), updated.blocked(), prior.blockerId(),
                    prior.targetId(), prior.revision(), prior.blocked());
        }
        if (changed != 1) throw changed();
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Block edge transaction is required");
        }
    }
    private static IllegalStateException changed() {
        return new IllegalStateException("Block edge changed");
    }
    @Override public String toString() { return "JdbcDirectionalBlockParticipant[private]"; }
}
