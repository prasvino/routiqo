package com.routiqo.core.routeupdate.api;

import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.PrivateAnchorChoice;
import com.routiqo.core.routeupdate.domain.PrivateAnchorChoiceSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserSignalJsonTest {
    private static final UUID JOURNEY = UUID.fromString(
            "10000000-0000-4000-8000-000000000001");
    private static final UUID ANCHOR = UUID.fromString(
            "10000000-0000-4000-8000-000000000002");
    private static final UUID CONTEXT = UUID.fromString(
            "10000000-0000-4000-8000-000000000003");

    @Test void mapsEveryClosedSignalValueAndPreservesLongPrecision() {
        for (QuickSignalValue value : QuickSignalValue.values()) {
            var parsed = BrowserSignalJson.acceptance(JOURNEY, request("""
                {"anchorId":"%s","value":"%s","contextId":"%s",\
                "routeRevision":"9007199254740993",\
                "consentGeneration":"9223372036854775807"}
                """.formatted(ANCHOR, value.name().toLowerCase(Locale.ROOT), CONTEXT)));
            assertThat(parsed.fingerprint().journeyId()).isEqualTo(JOURNEY);
            assertThat(parsed.fingerprint().anchorId()).isEqualTo(ANCHOR);
            assertThat(parsed.fingerprint().contextId()).isEqualTo(CONTEXT);
            assertThat(parsed.fingerprint().value()).isEqualTo(value);
            assertThat(parsed.fingerprint().routeRevision()).isEqualTo(9_007_199_254_740_993L);
            assertThat(parsed.fingerprint().consentGeneration()).isEqualTo(Long.MAX_VALUE);
            assertThat(parsed.toString()).doesNotContain(JOURNEY.toString(), ANCHOR.toString(),
                    CONTEXT.toString(), value.name(), "9007199254740993");
        }
    }

    @Test void expectedIssuePreservesExactLongsAndMaximumChoiceResponseStaysBounded() throws Exception {
        var issue = BrowserSignalJson.expectedIssue(request("""
                {"anchorId":"%s","contextId":"%s",\
                "routeRevision":"9007199254740993",\
                "consentGeneration":"9223372036854775807"}
                """.formatted(ANCHOR, CONTEXT)));
        assertThat(issue.anchorId()).isEqualTo(ANCHOR);
        assertThat(issue.expectation().contextId()).isEqualTo(CONTEXT);
        assertThat(issue.expectation().routeRevision()).isEqualTo(9_007_199_254_740_993L);
        assertThat(issue.expectation().consentGeneration()).isEqualTo(Long.MAX_VALUE);
        assertThat(issue.toString()).doesNotContain(ANCHOR.toString(), CONTEXT.toString(),
                "9007199254740993", Long.toString(Long.MAX_VALUE));

        var choices = new ArrayList<PrivateAnchorChoice>();
        String label = "🛣".repeat(80);
        for (int index = 1; index <= 128; index++) {
            choices.add(new PrivateAnchorChoice(new UUID(1, index), label,
                    Set.of(QuickSignalValue.Category.values())));
        }
        var snapshot = new PrivateAnchorChoiceSnapshot(CONTEXT, 9_007_199_254_740_993L,
                Long.MAX_VALUE, Instant.parse("2026-09-19T12:00:00.123456789Z"),
                Instant.parse("2026-09-19T12:15:00.123456789Z"), choices);
        String json = tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsString(BrowserSignalChoiceController.ChoiceResponse.from(snapshot));
        assertThat(json.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(256 * 1024);
        var node = tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
        assertThat(node.get("choices").size()).isEqualTo(128);
        assertThat(node.get("choices").get(0).get("displayLabel").textValue()).isEqualTo(label);
        assertThat(node.propertyNames()).containsExactlyInAnyOrder("contextId", "routeRevision",
                "consentGeneration", "issuedAt", "expiresAt", "choices");
    }

    @Test void duplicateTrailingMalformedUtf8AndNonCanonicalScalarsFailGenerically() {
        for (MockHttpServletRequest invalid : new MockHttpServletRequest[] {
                request("{\"anchorId\":\"" + ANCHOR + "\",\"anchorId\":\"" + ANCHOR
                        + "\"}"),
                request("{\"anchorId\":\"" + ANCHOR + "\"}{}"),
                request("{\"anchorId\":\"AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA\"}"),
                request(new byte[] {(byte) 0xc3, 0x28})}) {
            assertThatThrownBy(() -> BrowserSignalJson.issue(invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid signal request")
                    .hasNoCause();
        }
        assertThatThrownBy(() -> BrowserSignalJson.acceptance(JOURNEY, request("""
            {"anchorId":"%s","value":"queue_under_5","contextId":"%s",\
            "routeRevision":"01","consentGeneration":"9223372036854775808"}
            """.formatted(ANCHOR, CONTEXT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid signal request")
                .hasNoCause();
    }

    @Test void withdrawRequiresExactlyOneEmptyObject() {
        BrowserSignalJson.empty(request("{}"));
        for (String invalid : new String[] {"", "null", "[]", "{\"x\":1}", "{}{}"}) {
            assertThatThrownBy(() -> BrowserSignalJson.empty(request(invalid)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid signal request")
                    .hasNoCause();
        }
    }

    private static MockHttpServletRequest request(String body) {
        return request(body.getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequest request(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }
}
