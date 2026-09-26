package com.routiqo.core.spot.api;

import com.jayway.jsonpath.JsonPath;
import com.routiqo.core.spot.domain.SpotActivity;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NativeSpotJsonTest {
    private static final Instant NOW = Instant.parse("2026-11-05T06:30:00Z");

    private static SpotActivity full() {
        var entries = new ArrayList<SpotActivity.Entry>();
        for (int spot = 0; spot < 20; spot++) {
            var posts = new ArrayList<SpotActivity.PostView>();
            for (int post = 0; post < 10; post++)
                posts.add(new SpotActivity.PostView(UUID.randomUUID(), "Calm Auto", "த".repeat(200), "place",
                        NOW.minusSeconds(60L * (post * 20 + spot)), NOW.plusSeconds(3600), 0, null, false, false));
            var signal = new SpotActivity.SignalSummary(UUID.randomUUID(), "traffic", "slow",
                    List.of(new SpotActivity.ValueCount("slow", 2)), NOW, 0, null);
            entries.add(new SpotActivity.Entry(new UUID(0x4000L, spot + 1L), SpotActivity.State.LIVE,
                    List.of(signal), posts, List.of(), false));
        }
        return new SpotActivity(NOW, UUID.randomUUID(), entries);
    }

    @Test void oversizedActivityDropsTheOldestPostsButKeepsEverySummaryWithinTheCap() {
        byte[] body = NativeSpotJson.activityResponse(full());
        assertThat(body.length).isLessThanOrEqualTo(NativeSpotJson.MAX_RESPONSE_BYTES);
        String json = new String(body, StandardCharsets.UTF_8);
        assertThat(JsonPath.<List<Object>>read(json, "$.spots[*].signals[0]")).hasSize(20);
        List<Boolean> truncated = JsonPath.read(json, "$.spots[*].postsTruncated");
        assertThat(truncated).contains(true);
        int kept = JsonPath.<List<Object>>read(json, "$.spots[*].posts[*]").size();
        assertThat(kept).isLessThan(200).isGreaterThan(100);
        // Newest posts survive: every Spot keeps its first (newest) post.
        assertThat(JsonPath.<List<Object>>read(json, "$.spots[*].posts[0]")).hasSize(20);
    }

    @Test void activityWithinTheCapIsUntouched() {
        var small = new SpotActivity(NOW, UUID.randomUUID(), List.of(new SpotActivity.Entry(
                new UUID(0x4000L, 1L), SpotActivity.State.QUIET)));
        String json = new String(NativeSpotJson.activityResponse(small), StandardCharsets.UTF_8);
        assertThat(JsonPath.<Boolean>read(json, "$.spots[0].postsTruncated")).isFalse();
        assertThat(JsonPath.<List<Object>>read(json, "$.alerts")).isEmpty();
    }
}
