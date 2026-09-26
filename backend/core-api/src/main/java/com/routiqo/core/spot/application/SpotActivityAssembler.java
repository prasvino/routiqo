package com.routiqo.core.spot.application;

import com.routiqo.core.spot.application.SpotActivityReader.Contents;
import com.routiqo.core.spot.application.SpotActivityReader.PostRow;
import com.routiqo.core.spot.application.SpotActivityReader.SignalRow;
import com.routiqo.core.spot.application.SpotActivityReader.VoteRow;
import com.routiqo.core.spot.domain.SpotActivity;
import com.routiqo.core.spot.domain.SpotActivity.SignalSummary;
import com.routiqo.core.spot.domain.SpotActivity.ValueCount;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds one Spot's activity from current rows (SPOTS_SPEC, Spot state): live when an item was
 * captured or confirmed in the last 30 minutes, fading while unexpired items remain, quiet otherwise.
 */
final class SpotActivityAssembler {
    static final Duration LIVE_WINDOW = Duration.ofMinutes(30);

    private SpotActivityAssembler() {}

    static SpotActivity.Entry entry(UUID spotId, UUID viewer, Contents contents, Instant now) {
        Instant liveSince = now.minus(LIVE_WINDOW);
        List<SignalRow> signals = contents.signals().stream().filter(row -> row.spotId().equals(spotId)).toList();
        List<PostRow> posts = contents.posts().stream().filter(row -> row.spotId().equals(spotId))
                .sorted(Comparator.comparing(PostRow::effectiveCreated).reversed().thenComparing(PostRow::ref))
                .limit(10).toList();
        Map<UUID, List<VoteRow>> votes = contents.votes().stream()
                .collect(Collectors.groupingBy(VoteRow::itemRef));

        boolean live = false;
        var summaries = new ArrayList<SignalSummary>();
        var byCategory = new LinkedHashMap<String, List<SignalRow>>();
        signals.stream().sorted(Comparator.comparing(SignalRow::category))
                .forEach(row -> byCategory.computeIfAbsent(row.category(), key -> new ArrayList<>()).add(row));
        for (var category : byCategory.entrySet()) {
            var byValue = new LinkedHashMap<String, List<SignalRow>>();
            category.getValue().forEach(row -> byValue.computeIfAbsent(row.value(), key -> new ArrayList<>()).add(row));
            // Most reported value; ties go to the most recent report.
            var top = byValue.entrySet().stream().max(Comparator
                    .<Map.Entry<String, List<SignalRow>>>comparingInt(entry -> entry.getValue().size())
                    .thenComparing(entry -> latest(entry.getValue()))).orElseThrow();
            UUID ref = top.getValue().getFirst().groupRef();
            Instant window = top.getValue().stream().map(SignalRow::effectiveCreated)
                    .min(Instant::compareTo).orElseThrow();
            List<VoteRow> current = votes.getOrDefault(ref, List.of()).stream()
                    .filter(vote -> !vote.votedAt().isBefore(window)).toList();
            Instant latest = latest(category.getValue());
            live |= !latest.isBefore(liveSince) || confirmedSince(current, liveSince);
            summaries.add(new SignalSummary(ref, category.getKey(), top.getKey(),
                    byValue.entrySet().stream()
                            .sorted(Comparator.<Map.Entry<String, List<SignalRow>>>comparingInt(
                                    entry -> -entry.getValue().size()).thenComparing(Map.Entry::getKey))
                            .map(entry -> new ValueCount(entry.getKey(), entry.getValue().size())).toList(),
                    latest, stillTrue(current), viewerVote(current, viewer)));
        }
        var postViews = new ArrayList<SpotActivity.PostView>();
        for (PostRow post : posts) {
            List<VoteRow> current = votes.getOrDefault(post.ref(), List.of());
            live |= !post.effectiveCreated().isBefore(liveSince) || confirmedSince(current, liveSince);
            postViews.add(new SpotActivity.PostView(post.ref(), post.alias(), post.text(), post.type(),
                    post.effectiveCreated(), post.expiresAt(), stillTrue(current), viewerVote(current, viewer),
                    post.actorId().equals(viewer)));
        }
        var highlights = contents.highlights().stream().filter(row -> row.spotId().equals(spotId))
                .limit(3).map(row -> new SpotActivity.Highlight(row.text(), row.createdAt())).toList();
        SpotActivity.State state = live ? SpotActivity.State.LIVE
                : signals.isEmpty() && posts.isEmpty() ? SpotActivity.State.QUIET : SpotActivity.State.FADING;
        return new SpotActivity.Entry(spotId, state, summaries, postViews, highlights, false);
    }

    private static Instant latest(List<SignalRow> rows) {
        return rows.stream().map(SignalRow::effectiveCreated).max(Instant::compareTo).orElseThrow();
    }

    private static boolean confirmedSince(List<VoteRow> votes, Instant since) {
        return votes.stream().anyMatch(vote -> "STILL_TRUE".equals(vote.kind()) && !vote.votedAt().isBefore(since));
    }

    private static int stillTrue(List<VoteRow> votes) {
        return (int) votes.stream().filter(vote -> "STILL_TRUE".equals(vote.kind())).count();
    }

    private static String viewerVote(List<VoteRow> votes, UUID viewer) {
        return votes.stream().filter(vote -> vote.actorId().equals(viewer)).findFirst()
                .map(vote -> vote.kind().toLowerCase(Locale.ROOT)).orElse(null);
    }

}
