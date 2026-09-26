package com.routiqo.core.journey.infrastructure;

import com.routiqo.core.journey.application.ActiveJourneyReader;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Uses the partial unique index {@code journey_one_active_per_owner}; no transaction or lock. */
final class JdbcActiveJourneyReader implements ActiveJourneyReader {
    private final JdbcTemplate jdbc;

    JdbcActiveJourneyReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean hasActiveJourney(UUID ownerId) {
        if (ownerId == null) throw new IllegalArgumentException("Owner required");
        Boolean active = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM journey WHERE owner_id = ? AND status = 'ACTIVE')",
                Boolean.class, ownerId);
        return Boolean.TRUE.equals(active);
    }
}
