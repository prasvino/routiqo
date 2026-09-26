package com.routiqo.core.moderation.infrastructure;

import com.routiqo.core.identity.application.AccountAgeReader;
import com.routiqo.core.moderation.application.ContributionRestrictionReader;
import com.routiqo.core.moderation.application.ModerationAccountFacts;
import com.routiqo.core.moderation.domain.ContributorAssessment;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Account age from identity, restriction state from the same-transaction restriction snapshot, and a
 * bounded count of completed journeys (read-only; the count is capped at 1000).
 */
public final class JdbcModerationAccountFacts implements ModerationAccountFacts {
    private final JdbcTemplate jdbc;
    private final AccountAgeReader ages;
    private final ContributionRestrictionReader restrictions;
    private final Clock clock;

    public JdbcModerationAccountFacts(JdbcTemplate jdbc, AccountAgeReader ages,
            ContributionRestrictionReader restrictions, Clock clock) {
        this.jdbc = jdbc; this.ages = ages; this.restrictions = restrictions; this.clock = clock;
    }

    @Override public Facts read(UUID account) {
        long ageDays = ages.createdAt(account).map(created -> Math.max(0,
                Duration.between(created, clock.instant()).toDays())).orElse(0L);
        Integer journeys = jdbc.queryForObject("""
                SELECT count(*)::INTEGER FROM (SELECT 1 FROM journey WHERE owner_id = ? AND status = 'COMPLETED'
                    LIMIT 1000) completed
                """, Integer.class, account);
        ContributorAssessment restriction = restrictions.read(account);
        return new Facts(ageDays, journeys == null ? 0 : journeys,
                restriction.state() == ContributorAssessment.State.SUSPENDED, restriction.revision());
    }
}
