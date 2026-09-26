package com.routiqo.core.moderation.api;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0069/0075: two grant admins, shift grants of 1 to 12 hours, no self-grants, audited. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=consumer-client.apps.googleusercontent.com",
    "ROUTIQO_ADMIN_GOOGLE_CLIENT_ID=admin-client.apps.googleusercontent.com",
    "ROUTIQO_WEB_ORIGIN=http://localhost:3000", "ROUTIQO_ADMIN_ORIGIN=http://localhost:3001",
    "ROUTIQO_AUTH_SECURE_COOKIES=false", "ROUTIQO_ADMIN_ENABLED=true", "ROUTIQO_SPOTS_GRANT_ADMIN_ENABLED=true"
})
@ActiveProfiles({"persistence", "google-auth", "web-auth"})
@Import(AdminHttpTestSupport.FakeIdentity.class)
class AdminSpotGrantHttpTest extends AdminHttpTestSupport {
    @BeforeEach void reset() { jdbc.update("DELETE FROM auth_rate_bucket"); }

    static String issue(String permission, int minutes, UUID request) {
        return "{\"requestId\":\"" + request + "\",\"permission\":\"" + permission
                + "\",\"reason\":\"shift_start\",\"durationMinutes\":" + minutes + "}";
    }

    @Test void twoGrantAdminsGrantEachOtherQueuePermissionsButNeverThemselves() throws Exception {
        Browser first = operator("spots_grant_admin");
        Browser second = operator("spots_grant_admin");
        assertThat(JsonPath.<Boolean>read(first.get("spot-grants/me").body(), "$.canManageGrants")).isTrue();

        var issued = first.post("spot-grants/" + second.account + "/issue", issue("spots_review", 720, UUID.randomUUID()));
        assertThat(issued.statusCode()).isEqualTo(200);
        assertThat(second.post("spot-grants/" + first.account + "/issue",
                issue("spots_hide", 60, UUID.randomUUID())).statusCode()).isEqualTo(200);
        var review = first.get("spot-grants/" + second.account);
        assertThat(review.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<java.util.List<String>>read(review.body(), "$.grants[*].permission"))
                .containsExactly("spots_review");

        assertThat(first.post("spot-grants/" + first.account + "/issue",
                issue("spots_review", 60, UUID.randomUUID())).statusCode()).isEqualTo(404); // No self-grant.
        assertThat(first.post("spot-grants/" + second.account + "/issue",
                issue("spots_grant_admin", 60, UUID.randomUUID())).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_grant_action_audit", Integer.class)).isEqualTo(2);
    }

    @Test void shiftGrantsAreOneToTwelveHoursAndLiveGrantsMustBeRevokedFirst() throws Exception {
        Browser admin = operator("spots_grant_admin");
        UUID moderator = account("moderator-" + UUID.randomUUID());
        String base = "spot-grants/" + moderator;
        assertThat(admin.post(base + "/issue", issue("spots_review", 59, UUID.randomUUID())).statusCode()).isEqualTo(400);
        assertThat(admin.post(base + "/issue", issue("spots_review", 721, UUID.randomUUID())).statusCode()).isEqualTo(400);
        UUID request = UUID.randomUUID();
        var first = admin.post(base + "/issue", issue("spots_review", 480, request));
        assertThat(first.statusCode()).isEqualTo(200);
        // Exact retry replays without extending; a changed retry conflicts; a live grant can't be reissued.
        var replay = admin.post(base + "/issue", issue("spots_review", 480, request));
        assertThat(JsonPath.<Boolean>read(replay.body(), "$.replayed")).isTrue();
        assertThat(JsonPath.<String>read(replay.body(), "$.expiresAt"))
                .isEqualTo(JsonPath.<String>read(first.body(), "$.expiresAt"));
        assertThat(admin.post(base + "/issue", issue("spots_review", 60, request)).statusCode()).isEqualTo(409);
        assertThat(admin.post(base + "/issue", issue("spots_review", 60, UUID.randomUUID())).statusCode()).isEqualTo(409);
        String revoke = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"permission\":\"spots_review\",\"reason\":\"coverage_change\"}";
        assertThat(admin.post(base + "/revoke", revoke).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM moderation_operator_grant WHERE operator_id = ?",
                Integer.class, moderator)).isZero();
        assertThat(jdbc.queryForList("SELECT reason FROM spot_grant_action_audit WHERE target_id = ? ORDER BY occurred_at",
                String.class, moderator)).containsExactly("SHIFT_START", "COVERAGE_CHANGE");
    }

    @Test void onlyACurrentGrantAdminManagesGrants() throws Exception {
        Browser moderator = operator("spots_review", "spots_hide");
        UUID other = account("other-" + UUID.randomUUID());
        assertThat(JsonPath.<Boolean>read(moderator.get("spot-grants/me").body(), "$.canManageGrants")).isFalse();
        assertThat(moderator.post("spot-grants/" + other + "/issue", issue("spots_review", 60, UUID.randomUUID()))
                .statusCode()).isEqualTo(403);
        Browser v3Admin = operator("traffic_grant_admin");
        assertThat(v3Admin.post("spot-grants/" + other + "/issue", issue("spots_review", 60, UUID.randomUUID()))
                .statusCode()).isEqualTo(403);
    }

    @Test void grantRequestsAreStrictJsonUnderCsrf() throws Exception {
        Browser admin = operator("spots_grant_admin");
        UUID target = account("strict-" + UUID.randomUUID());
        String path = "spot-grants/" + target + "/issue";
        assertThat(admin.post(path, "{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"permission\":\"spots_review\",\"reason\":\"shift_start\",\"durationMinutes\":60,\"x\":1}")
                .statusCode()).isEqualTo(400);
        assertThat(admin.post(path, issue("spots_review", 60, UUID.randomUUID()).replace("shift_start", "SHIFT_START"))
                .statusCode()).isEqualTo(400);
        var noCsrf = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                "http://localhost:" + port + "/api/v1/admin/" + path)).header("Origin", ADMIN_ORIGIN)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(issue("spots_review", 60, UUID.randomUUID())))
                .build();
        assertThat(admin.client.send(noCsrf, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(403);
    }
}
