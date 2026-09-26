package com.routiqo.core.spot.infrastructure;

import com.routiqo.core.spot.application.SpotContributionStore;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** PostgreSQL participant for Spot contributions; every method joins the caller's transaction. */
final class JdbcSpotContributionStore implements SpotContributionStore {
    private final JdbcTemplate jdbc;

    JdbcSpotContributionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Spot contribution writes need the account transaction");
    }

    private static Timestamp at(Instant instant) { return Timestamp.from(instant); }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getTimestamp(column).toInstant();
    }

    @Override public Optional<KeyRecord> findKey(UUID actorId, UUID clientKey) {
        requireTransaction();
        return jdbc.query("""
                SELECT kind, ref, fingerprint FROM spot_contribution_key
                WHERE actor_id = ? AND client_key = ? FOR UPDATE
                """, (row, index) -> new KeyRecord(Kind.valueOf(row.getString("kind")),
                        row.getObject("ref", UUID.class), row.getString("fingerprint")),
                actorId, clientKey).stream().findFirst();
    }

    @Override public void insertKey(UUID actorId, UUID clientKey, Kind kind, UUID ref, String fingerprint,
            Instant now) {
        requireTransaction();
        jdbc.update("""
                INSERT INTO spot_contribution_key (actor_id, client_key, kind, ref, fingerprint, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, actorId, clientKey, kind.name(), ref, fingerprint, at(now));
    }

    @Override public Optional<ItemState> item(UUID ref) {
        requireTransaction();
        List<ItemState> found = jdbc.query("""
                SELECT ref, 'SIGNAL' AS kind, state, expires_at, NULL AS alias FROM spot_signal WHERE ref = ?
                UNION ALL
                SELECT ref, 'POST' AS kind, state, expires_at, alias FROM spot_post WHERE ref = ?
                """, (row, index) -> new ItemState(row.getObject("ref", UUID.class),
                        Kind.valueOf(row.getString("kind")), row.getString("state"),
                        instant(row, "expires_at"), row.getString("alias")), ref, ref);
        return found.stream().findFirst();
    }

    @Override public int countCharges(UUID actorId, Action action, Instant since) {
        requireTransaction();
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM spot_contribution_ledger
                WHERE actor_id = ? AND action = ? AND charged_at > ?
                """, Integer.class, actorId, action.name(), at(since));
        return count == null ? 0 : count;
    }

    @Override public boolean chargedFor(UUID actorId, Action action, UUID spotId, String category,
            Instant since) {
        requireTransaction();
        Boolean found = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM spot_contribution_ledger
                    WHERE actor_id = ? AND action = ? AND spot_id = ? AND category = ? AND charged_at > ?)
                """, Boolean.class, actorId, action.name(), spotId, category, at(since));
        return Boolean.TRUE.equals(found);
    }

    @Override public void charge(UUID actorId, Action action, UUID spotId, String category, Instant now) {
        requireTransaction();
        jdbc.update("""
                INSERT INTO spot_contribution_ledger (actor_id, action, spot_id, category, charged_at)
                VALUES (?, ?, ?, ?, ?)
                """, actorId, action.name(), spotId, category, at(now));
    }

    @Override public UUID groupRef(UUID spotId, String category, String value, UUID candidate) {
        requireTransaction();
        jdbc.update("""
                INSERT INTO spot_signal_group (ref, spot_id, category, value) VALUES (?, ?, ?, ?)
                ON CONFLICT (spot_id, category, value) DO NOTHING
                """, candidate, spotId, category, value);
        return jdbc.queryForObject("""
                SELECT ref FROM spot_signal_group WHERE spot_id = ? AND category = ? AND value = ?
                """, UUID.class, spotId, category, value);
    }

    @Override public void supersedeActiveSignal(UUID actorId, UUID spotId, String category, Instant now) {
        requireTransaction();
        jdbc.update("""
                UPDATE spot_signal SET state = 'SUPERSEDED', ended_at = ?
                WHERE actor_id = ? AND spot_id = ? AND category = ? AND state = 'ACTIVE'
                """, at(now), actorId, spotId, category);
    }

    @Override public void insertSignal(NewSignal s) {
        requireTransaction();
        jdbc.update("""
                INSERT INTO spot_signal (ref, actor_id, journey_id, spot_id, catalog_version, group_ref,
                    category, value, captured_at, received_at, effective_created_at, expires_at,
                    max_expires_at, state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, s.ref(), s.actorId(), s.journeyId(), s.spotId(), s.catalogVersion(), s.groupRef(),
                s.category(), s.value(), at(s.capturedAt()), at(s.receivedAt()),
                at(s.timing().effectiveCreated()), at(s.timing().expiresAt()), at(s.timing().maxExpiresAt()));
    }

    @Override public Optional<String> alias(UUID spotId, LocalDate roomDay, UUID actorId) {
        requireTransaction();
        return jdbc.queryForList("""
                SELECT alias FROM spot_alias WHERE spot_id = ? AND room_day = ? AND actor_id = ?
                """, String.class, spotId, Date.valueOf(roomDay), actorId).stream().findFirst();
    }

    @Override public boolean aliasTaken(UUID spotId, LocalDate roomDay, String alias) {
        requireTransaction();
        Boolean taken = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM spot_alias WHERE spot_id = ? AND room_day = ? AND alias = ?)
                """, Boolean.class, spotId, Date.valueOf(roomDay), alias);
        return Boolean.TRUE.equals(taken);
    }

    @Override public void insertAlias(UUID spotId, LocalDate roomDay, UUID actorId, String alias, Instant now) {
        requireTransaction();
        jdbc.update("""
                INSERT INTO spot_alias (spot_id, room_day, actor_id, alias, created_at) VALUES (?, ?, ?, ?, ?)
                """, spotId, Date.valueOf(roomDay), actorId, alias, at(now));
    }

    @Override public void insertPost(NewPost p) {
        requireTransaction();
        jdbc.update("""
                INSERT INTO spot_post (ref, actor_id, journey_id, spot_id, catalog_version, type, text,
                    room_day, alias, captured_at, received_at, effective_created_at, expires_at,
                    max_expires_at, state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, p.ref(), p.actorId(), p.journeyId(), p.spotId(), p.catalogVersion(), p.type(), p.text(),
                Date.valueOf(p.roomDay()), p.alias(), at(p.capturedAt()), at(p.receivedAt()),
                at(p.timing().effectiveCreated()), at(p.timing().expiresAt()), at(p.timing().maxExpiresAt()));
    }

    @Override public Optional<LockedPost> lockPost(UUID ref) {
        requireTransaction();
        return jdbc.query("""
                SELECT ref, actor_id, type, state, effective_created_at, expires_at, max_expires_at
                FROM spot_post WHERE ref = ? FOR UPDATE
                """, (row, index) -> new LockedPost(row.getObject("ref", UUID.class),
                        row.getObject("actor_id", UUID.class), row.getString("type"), row.getString("state"),
                        instant(row, "effective_created_at"), instant(row, "expires_at"),
                        instant(row, "max_expires_at")), ref).stream().findFirst();
    }

    @Override public boolean lockGroup(UUID groupRef) {
        requireTransaction();
        // NO KEY UPDATE serializes voters without blocking FK key-share from concurrent signal inserts.
        return !jdbc.queryForList("SELECT ref FROM spot_signal_group WHERE ref = ? FOR NO KEY UPDATE",
                UUID.class, groupRef).isEmpty();
    }

    @Override public List<LockedSignal> lockActiveSignals(UUID groupRef, Instant now) {
        requireTransaction();
        return jdbc.query("""
                SELECT ref, actor_id, category, effective_created_at, expires_at, max_expires_at
                FROM spot_signal WHERE group_ref = ? AND state = 'ACTIVE' AND expires_at > ?
                ORDER BY ref FOR UPDATE
                """, (row, index) -> new LockedSignal(row.getObject("ref", UUID.class),
                        row.getObject("actor_id", UUID.class), row.getString("category"),
                        instant(row, "effective_created_at"), instant(row, "expires_at"),
                        instant(row, "max_expires_at")), groupRef, at(now));
    }

    @Override public Optional<VoteKind> vote(UUID itemRef, UUID actorId, Instant since) {
        requireTransaction();
        return jdbc.queryForList(
                "SELECT kind FROM spot_vote WHERE item_ref = ? AND actor_id = ? AND voted_at >= ?",
                String.class, itemRef, actorId, at(since)).stream().findFirst().map(VoteKind::valueOf);
    }

    @Override public void deleteHighlightOf(UUID postRef) {
        requireTransaction();
        jdbc.update("DELETE FROM spot_highlight WHERE source_post_ref = ?", postRef);
    }

    @Override public void lockRoom(UUID spotId, LocalDate roomDay) {
        requireTransaction();
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                "spot-room:" + spotId + ":" + roomDay);
    }

    @Override public void putVote(UUID itemRef, UUID actorId, VoteKind kind, Instant now) {
        requireTransaction();
        jdbc.update("""
                INSERT INTO spot_vote (item_ref, actor_id, kind, voted_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (item_ref, actor_id) DO UPDATE SET kind = excluded.kind, voted_at = excluded.voted_at
                """, itemRef, actorId, kind.name(), at(now));
    }

    @Override public int countVotes(UUID itemRef, VoteKind kind, Instant since, List<UUID> excludedActors) {
        requireTransaction();
        UUID[] excluded = excludedActors.toArray(UUID[]::new);
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM spot_vote
                WHERE item_ref = ? AND kind = ? AND voted_at >= ? AND NOT (actor_id = ANY (?))
                """, Integer.class, itemRef, kind.name(), at(since), excluded);
        return count == null ? 0 : count;
    }

    @Override public void extendPost(UUID ref, Instant expiresAt) {
        requireTransaction();
        jdbc.update("UPDATE spot_post SET expires_at = ? WHERE ref = ? AND state = 'ACTIVE' AND expires_at < ?",
                at(expiresAt), ref, at(expiresAt));
    }

    @Override public void extendSignal(UUID ref, Instant expiresAt) {
        requireTransaction();
        jdbc.update("UPDATE spot_signal SET expires_at = ? WHERE ref = ? AND state = 'ACTIVE' AND expires_at < ?",
                at(expiresAt), ref, at(expiresAt));
    }

    @Override public void endPost(UUID ref, String state, Instant now) {
        requireTransaction();
        if (!state.equals("DELETED") && !state.equals("EXPIRED_EARLY"))
            throw new IllegalArgumentException("Invalid post end state");
        jdbc.update("""
                UPDATE spot_post SET state = ?, ended_at = ?,
                    expires_at = CASE WHEN ? = 'EXPIRED_EARLY' THEN LEAST(expires_at, ?) ELSE expires_at END
                WHERE ref = ? AND state = 'ACTIVE'
                """, state, at(now), state, at(now), ref);
    }

    @Override public void endSignal(UUID ref, Instant now) {
        requireTransaction();
        jdbc.update("""
                UPDATE spot_signal SET state = 'EXPIRED_EARLY', ended_at = ?, expires_at = LEAST(expires_at, ?)
                WHERE ref = ? AND state = 'ACTIVE'
                """, at(now), at(now), ref);
    }
}
