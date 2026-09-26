package com.routiqo.core.spot.infrastructure;

import com.routiqo.core.spot.application.SpotActivityReader;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded, indexed reads of current Spot content; no locks, no logging of the requested Spots. */
final class JdbcSpotActivityReader implements SpotActivityReader {
    private final JdbcTemplate jdbc;

    JdbcSpotActivityReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Contents read(List<UUID> spotIds, Instant now, Set<UUID> hiddenAuthors) {
        if (spotIds.isEmpty() || spotIds.size() > 20) throw new IllegalArgumentException("Invalid Spot list");
        UUID[] ids = spotIds.toArray(UUID[]::new);
        UUID[] hidden = hiddenAuthors.toArray(UUID[]::new);
        Timestamp at = Timestamp.from(now);
        List<SignalRow> signals = jdbc.query("""
                SELECT spot_id, group_ref, category, value, actor_id, effective_created_at FROM spot_signal
                WHERE spot_id = ANY (?) AND state = 'ACTIVE' AND expires_at > ?
                """, (row, index) -> new SignalRow(row.getObject("spot_id", UUID.class),
                        row.getObject("group_ref", UUID.class), row.getString("category"), row.getString("value"),
                        row.getObject("actor_id", UUID.class), row.getTimestamp("effective_created_at").toInstant()),
                ids, at);
        List<PostRow> posts = jdbc.query("""
                SELECT spot_id, ref, actor_id, type, text, alias, effective_created_at, expires_at FROM (
                    SELECT p.*, row_number() OVER (PARTITION BY spot_id
                        ORDER BY effective_created_at DESC, ref) AS position
                    FROM spot_post p WHERE spot_id = ANY (?) AND state = 'ACTIVE' AND expires_at > ?
                      AND NOT (actor_id = ANY (?))) newest
                WHERE position <= 10
                """, (row, index) -> new PostRow(row.getObject("spot_id", UUID.class),
                        row.getObject("ref", UUID.class), row.getObject("actor_id", UUID.class),
                        row.getString("type"), row.getString("text"), row.getString("alias"),
                        row.getTimestamp("effective_created_at").toInstant(),
                        row.getTimestamp("expires_at").toInstant()), ids, at, hidden);
        var refs = new ArrayList<UUID>();
        signals.forEach(row -> refs.add(row.groupRef()));
        posts.forEach(row -> refs.add(row.ref()));
        List<VoteRow> votes = refs.isEmpty() ? List.of() : jdbc.query("""
                SELECT item_ref, actor_id, kind, voted_at FROM spot_vote
                WHERE item_ref = ANY (?) AND NOT (actor_id = ANY (?))
                """, (row, index) -> new VoteRow(row.getObject("item_ref", UUID.class),
                        row.getObject("actor_id", UUID.class), row.getString("kind"),
                        row.getTimestamp("voted_at").toInstant()), refs.stream().distinct().toArray(UUID[]::new),
                        hidden);
        List<HighlightRow> highlights = jdbc.query("""
                SELECT spot_id, text, created_at FROM (
                    SELECT h.*, row_number() OVER (PARTITION BY spot_id
                        ORDER BY still_true DESC, created_at DESC) AS position
                    FROM spot_highlight h WHERE spot_id = ANY (?) AND expires_at > ?) best
                WHERE position <= 3
                """, (row, index) -> new HighlightRow(row.getObject("spot_id", UUID.class), row.getString("text"),
                        row.getTimestamp("created_at").toInstant()), ids, at);
        return new Contents(signals, posts, votes, highlights);
    }
}
