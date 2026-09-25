package com.routiqo.core.publiclive;

import com.routiqo.core.moderation.infrastructure.JdbcTrafficReview;
import com.routiqo.core.publiclive.api.BrowserCommunityTrafficController;
import com.routiqo.core.publiclive.api.BrowserCommunityTrafficV3Controller;
import com.routiqo.core.publiclive.application.CommunityTrafficCandidateStore;
import com.routiqo.core.publiclive.infrastructure.JdbcCommunityTrafficV3;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0065 privacy invariants for community traffic outputs: responses have exact, contributor-free
 * field sets, internal records redact themselves, and logging in these modules never carries data.
 */
class CommunityLivePrivacyTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID();
    private static final Instant T = Instant.parse("2026-09-25T10:00:00Z");

    private static Set<String> keys(Object value) {
        JsonNode node = JSON.valueToTree(value);
        return Set.copyOf(node.propertyNames());
    }

    @Test void publicFeedCarriesNoContributorCountsIdentitiesOrIndividualTimes() {
        var moment = new JdbcCommunityTrafficV3.Moment(A, "Coarse area", "traffic_slow",
                "10:00–10:05 UTC", T, "community", 3);
        assertThat(keys(moment)).containsExactlyInAnyOrder("ref", "areaLabel", "trafficValue",
                "observationPeriod", "expiresAt", "source", "schemaVersion");
        var feed = new JdbcCommunityTrafficV3.Feed(3, T, List.of(moment));
        assertThat(keys(feed)).containsExactlyInAnyOrder("schemaVersion", "serverTime", "moments");
    }

    @Test void ownerAndReportResponsesCarryOnlyTheCallersOwnHandles() {
        assertThat(keys(new BrowserCommunityTrafficController.Handle(A, B, C, A, "expired", T, T)))
                .containsExactlyInAnyOrder("candidateId", "journeyId", "commandId", "requestId",
                        "status", "acceptedAt", "windowEndsAt");
        assertThat(keys(new BrowserCommunityTrafficController.ShareResponse(A, B,
                "accepted_for_consideration", T, T)))
                .containsExactlyInAnyOrder("candidateId", "commandId", "status", "acceptedAt", "windowEndsAt");
        assertThat(keys(new BrowserCommunityTrafficV3Controller.ReportResponse("received", T, T)))
                .containsExactlyInAnyOrder("status", "receivedAt", "receiptExpiresAt");
    }

    @Test void moderatorQueueShowsReportReasonCountsButNoReporterOrContributorData() {
        var item = new JdbcTrafficReview.Item(A, Map.of("INACCURATE", 1, "UNSAFE", 0, "SPAM", 0),
                "AVAILABLE", "Coarse area", "TRAFFIC_SLOW", "10:00–10:05 UTC", T);
        assertThat(keys(item)).containsExactlyInAnyOrder("ref", "reasonCounts", "evidenceStatus",
                "areaLabel", "trafficValue", "observationPeriod", "expiresAt");
    }

    @Test void candidateRecordRedactsItselfForLogs() {
        UUID actor = UUID.randomUUID(), anchor = UUID.randomUUID();
        var candidate = new CommunityTrafficCandidateStore.Candidate(A, actor, B, C, A, anchor,
                "TRAFFIC_SLOW", T, B, 7, T.plusSeconds(20), T.plusSeconds(30), T.plusSeconds(3600),
                CommunityTrafficCandidateStore.State.ACTIVE);
        assertThat(candidate.toString()).doesNotContain(actor.toString(), anchor.toString(),
                "TRAFFIC_SLOW", T.toString(), A.toString());
    }

    @Test void communityAndModerationLoggingUsesConstantMessagesOnly() throws IOException {
        // A log call whose first argument is not a single string literal, or that passes any further
        // argument (placeholders, exceptions carrying SQL/IDs), could recreate what the API withholds.
        Pattern call = Pattern.compile("\\b(?:LOG|log|logger|LOGGER)\\s*\\.\\s*(?:trace|debug|info|warn|error)\\s*\\(([^;]*)\\)\\s*;");
        Pattern constant = Pattern.compile("\\s*\"(?:[^\"\\\\]|\\\\.)*\"\\s*");
        List<String> violations = new ArrayList<>();
        for (String module : List.of("publiclive", "moderation")) {
            try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/routiqo/core", module))) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    var matcher = call.matcher(source);
                    while (matcher.find())
                        if (!constant.matcher(matcher.group(1)).matches())
                            violations.add(file.getFileName() + ": " + matcher.group().strip());
                    if (source.contains("System.out") || source.contains("printStackTrace"))
                        violations.add(file.getFileName() + ": console output");
                }
            }
        }
        assertThat(violations).isEmpty();
    }
}
