package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.publiclive.application.CommunityTrafficCandidateStore;
import com.routiqo.core.publiclive.application.CommunityTrafficConflict;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Account-serialized V3 candidate and debit writes. The database constraints are final guards. */
public final class JdbcCommunityTrafficCandidateStore implements CommunityTrafficCandidateStore {
    private final JdbcTemplate jdbc;

    public JdbcCommunityTrafficCandidateStore(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public Candidate find(UUID actorId, UUID commandId) {
        transaction();
        List<Candidate> rows = jdbc.query("""
            SELECT candidate_id, actor_id, journey_id, command_id, request_id, anchor_id,
                   traffic_value, window_start, catalog_version, consent_generation,
                   received_at, created_at, expires_at, state
            FROM community_traffic_candidate_v3
            WHERE actor_id = ? AND command_id = ? FOR UPDATE
            """, JdbcCommunityTrafficCandidateStore::map, actorId, commandId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override public Candidate insert(Candidate candidate) {
        transaction();
        // The account authority lock serializes same-owner calls. The SQL predicate is an
        // independent guard in case a caller is ever wired without that authority.
        int debit = jdbc.update("""
            INSERT INTO community_traffic_daily_debit_v3(actor_id, utc_day, used)
            VALUES (?, ?, 1)
            ON CONFLICT (actor_id, utc_day) DO UPDATE
                SET used = community_traffic_daily_debit_v3.used + 1
            WHERE community_traffic_daily_debit_v3.used < 12
            """, candidate.actorId(), candidate.acceptedAt().atOffset(ZoneOffset.UTC).toLocalDate());
        if (debit != 1) throw new CommunityTrafficConflict();
        int inserted = jdbc.update("""
            INSERT INTO community_traffic_candidate_v3
                (candidate_id, actor_id, journey_id, command_id, request_id, anchor_id,
                 traffic_value, window_start, catalog_version, consent_generation,
                 state, received_at, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
            ON CONFLICT DO NOTHING
            """, candidate.candidateId(), candidate.actorId(), candidate.journeyId(),
                candidate.commandId(), candidate.requestId(), candidate.anchorId(),
                candidate.trafficValue(), Timestamp.from(candidate.windowStart()),
                candidate.catalogVersion(), candidate.consentGeneration(),
                Timestamp.from(candidate.receivedAt()), Timestamp.from(candidate.acceptedAt()),
                Timestamp.from(candidate.expiresAt()));
        if (inserted != 1) throw new CommunityTrafficConflict();
        return candidate;
    }

    @Override public Candidate stop(UUID actorId, UUID journeyId, UUID commandId, Instant at) {
        transaction();
        Candidate prior = find(actorId, commandId);
        if (prior == null || !prior.journeyId().equals(journeyId))
            throw new SecurityException("Community traffic share unavailable");
        if (prior.state() == State.STOPPED) return prior;
        int changed = jdbc.update("""
            UPDATE community_traffic_candidate_v3 SET state = 'STOPPED', stopped_at = ?
            WHERE actor_id = ? AND journey_id = ? AND command_id = ? AND state = 'ACTIVE'
            """, Timestamp.from(at), actorId, journeyId, commandId);
        if (changed != 1) throw new CommunityTrafficConflict();
        return find(actorId, commandId);
    }

    @Override public List<Candidate> recent(UUID actorId, Instant since) {
        transaction();
        return jdbc.query("""
            SELECT candidate_id, actor_id, journey_id, command_id, request_id, anchor_id,
                   traffic_value, window_start, catalog_version, consent_generation,
                   received_at, created_at, expires_at, state
            FROM community_traffic_candidate_v3
            WHERE actor_id = ? AND created_at > ? AND expires_at > clock_timestamp()
            ORDER BY created_at DESC, candidate_id DESC LIMIT 100
            """, JdbcCommunityTrafficCandidateStore::map, actorId, Timestamp.from(since));
    }

    private static Candidate map(ResultSet row, int ignored) throws SQLException {
        return new Candidate(row.getObject("candidate_id", UUID.class),
                row.getObject("actor_id", UUID.class), row.getObject("journey_id", UUID.class),
                row.getObject("command_id", UUID.class), row.getObject("request_id", UUID.class),
                row.getObject("anchor_id", UUID.class), row.getString("traffic_value"),
                row.getTimestamp("window_start").toInstant(),
                row.getObject("catalog_version", UUID.class), row.getLong("consent_generation"),
                row.getTimestamp("received_at").toInstant(),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("expires_at").toInstant(),
                State.valueOf(row.getString("state")));
    }

    private static void transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Community traffic owner transaction required");
    }
}
