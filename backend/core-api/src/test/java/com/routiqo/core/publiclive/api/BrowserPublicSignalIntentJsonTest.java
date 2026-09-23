package com.routiqo.core.publiclive.api;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserPublicSignalIntentJsonTest {
    private static final UUID REQUEST = UUID.fromString("10000000-0000-4000-8000-000000000001");

    @Test void exactPurposeAndRequestIdAreRequired() {
        assertThat(BrowserPublicSignalIntentJson.share(request("""
                {"requestId":"%s","purpose":"public-live-moment-v1"}
                """.formatted(REQUEST)))).isEqualTo(REQUEST);
        for (String invalid : new String[] {
                "{}", "null", "[]", "", "{}{}",
                "{\"requestId\":\"" + REQUEST + "\",\"purpose\":\"private\"}",
                "{\"requestId\":\"" + REQUEST + "\",\"purpose\":\"public-live-moment-v1\",\"anchorId\":\"" + REQUEST + "\"}",
                "{\"requestId\":\"" + REQUEST + "\",\"requestId\":\"" + REQUEST + "\",\"purpose\":\"public-live-moment-v1\"}",
                "{\"requestId\":\"00000000-0000-0000-0000-000000000000\",\"purpose\":\"public-live-moment-v1\"}"}) {
            assertThatThrownBy(() -> BrowserPublicSignalIntentJson.share(request(invalid)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid public intent request")
                    .hasNoCause();
        }
        var malformedUtf8 = new MockHttpServletRequest();
        malformedUtf8.setContentType("application/json");
        malformedUtf8.setContent(new byte[] {(byte) 0xc3, 0x28});
        assertThatThrownBy(() -> BrowserPublicSignalIntentJson.share(malformedUtf8))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> BrowserPublicSignalIntentJson.share(
                request(" ".repeat(20 * 1024 + 1))))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @Test void stopHasNoClientSuppliedContext() {
        BrowserPublicSignalIntentJson.stop(request("{}"));
        for (String invalid : new String[] {"", "null", "[]", "{\"requestId\":\"" + REQUEST + "\"}", "{}{}"}) {
            assertThatThrownBy(() -> BrowserPublicSignalIntentJson.stop(request(invalid)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
