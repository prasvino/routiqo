package com.routiqo.core.moderation.infrastructure;

import com.routiqo.core.moderation.application.BlockedAccountsReader;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class JdbcBlockedAccountsReader implements BlockedAccountsReader {
    private final JdbcTemplate jdbc;

    JdbcBlockedAccountsReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Set<UUID> blockedBy(UUID viewerId) {
        if (viewerId == null) throw new IllegalArgumentException("Viewer required");
        return Set.copyOf(jdbc.queryForList(
                "SELECT target_id FROM live_block_edge WHERE blocker_id = ? AND blocked LIMIT 200",
                UUID.class, viewerId));
    }
}
