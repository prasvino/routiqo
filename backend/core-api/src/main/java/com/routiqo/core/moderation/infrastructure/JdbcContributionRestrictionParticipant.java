package com.routiqo.core.moderation.infrastructure;

import com.routiqo.core.moderation.application.ContributionRestrictionParticipant;
import com.routiqo.core.moderation.domain.ContributorAssessment;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Restricted storage projection: no assessment references or timestamps are persisted. */
public final class JdbcContributionRestrictionParticipant implements ContributionRestrictionParticipant {
    private final JdbcTemplate jdbc;

    public JdbcContributionRestrictionParticipant(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public ContributorAssessment read(UUID actorId) {
        requireTransaction();
        List<ContributorAssessment> rows = jdbc.query("""
            SELECT revision, restricted FROM live_contribution_restriction WHERE actor_id = ?
            """, (row, number) -> new ContributorAssessment(actorId, row.getLong("revision"),
                    row.getBoolean("restricted") ? ContributorAssessment.State.SUSPENDED
                            : ContributorAssessment.State.UNASSESSED,
                    null, null, null), actorId);
        return rows.isEmpty() ? ContributorAssessment.initial(actorId) : rows.getFirst();
    }

    @Override
    public void replace(ContributorAssessment prior, ContributorAssessment updated) {
        requireTransaction();
        if (!prior.actorId().equals(updated.actorId())
                || prior.state() == ContributorAssessment.State.ASSESSED
                || updated.state() == ContributorAssessment.State.ASSESSED
                || prior.revision() == Long.MAX_VALUE
                || updated.revision() != prior.revision() + 1
                || updated.state() == ContributorAssessment.State.UNASSESSED
                        && prior.state() != ContributorAssessment.State.SUSPENDED) throw denied();
        int changed;
        if (prior.revision() == 0) {
            changed = jdbc.update("""
                INSERT INTO live_contribution_restriction(actor_id, revision, restricted)
                VALUES (?, ?, ?) ON CONFLICT (actor_id) DO NOTHING
                """, updated.actorId(), updated.revision(),
                    updated.state() == ContributorAssessment.State.SUSPENDED);
        } else {
            changed = jdbc.update("""
                UPDATE live_contribution_restriction SET revision = ?, restricted = ?
                WHERE actor_id = ? AND revision = ? AND restricted = ?
                """, updated.revision(),
                    updated.state() == ContributorAssessment.State.SUSPENDED,
                    prior.actorId(), prior.revision(),
                    prior.state() == ContributorAssessment.State.SUSPENDED);
        }
        if (changed != 1) throw denied();
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Contribution restriction transaction is required");
        }
    }

    private static IllegalStateException denied() {
        return new IllegalStateException("Contribution restriction changed");
    }

    @Override public String toString() { return "JdbcContributionRestrictionParticipant[private]"; }
}
