package com.routiqo.core.publiclive.api;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.publiclive.infrastructure.JdbcCommunityTrafficV3;
import com.routiqo.core.security.BrowserAuthPolicy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BrowserCommunityTrafficV3ControllerTest {
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID JOURNEY = UUID.randomUUID();
    private static final UUID REF = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private final JdbcCommunityTrafficV3 store = mock(JdbcCommunityTrafficV3.class);
    private final GoogleSessionService sessions = mock(GoogleSessionService.class);
    private final BrowserAuthPolicy policy = mock(BrowserAuthPolicy.class);
    private final AuthRateGate rates = mock(AuthRateGate.class);

    @Test void readBindsAccountAndJourneyAndRejectsCallerAnchor() {
        when(sessions.authenticate(any())).thenReturn(ACTOR);
        when(rates.allow(ACTOR.toString(), "community-traffic-v3-read", 12)).thenReturn(true);
        Instant serverTime = Instant.parse("2026-09-23T10:05:00Z");
        when(store.read(ACTOR, JOURNEY)).thenReturn(new JdbcCommunityTrafficV3.Feed(3,
                serverTime, List.of()));
        var controller = controller();
        var feed = controller.read(JOURNEY.toString(), request(ACTOR, null)).getBody();
        assertThat(feed.moments()).isEmpty();
        assertThat(feed.serverTime()).isEqualTo(serverTime);
        verify(store).read(ACTOR, JOURNEY);
        var query = request(ACTOR, null);
        query.setQueryString("anchor=" + UUID.randomUUID());
        assertThatThrownBy(() -> controller.read(JOURNEY.toString(), query))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.read(JOURNEY.toString(), request(UUID.randomUUID(), null)))
                .isInstanceOf(SecurityException.class);
        verifyNoMoreInteractions(store);
    }

    @Test void reportHasExactBoundedPayloadAndNeverAcceptsClientAnchor() {
        when(sessions.authenticate(any())).thenReturn(ACTOR);
        when(rates.allow(ACTOR.toString(), "community-traffic-v3-report", 5)).thenReturn(true);
        var received = java.time.Instant.parse("2026-09-25T10:00:00Z");
        when(store.report(ACTOR, JOURNEY, REF, REQUEST, "INACCURATE")).thenReturn(
                new com.routiqo.core.publiclive.infrastructure.JdbcCommunityTrafficV3.Receipt(
                        received, received.plusSeconds(168L * 3600)));
        var controller = controller();
        var body = "{\"reason\":\"INACCURATE\",\"clientRequestId\":\"" + REQUEST + "\"}";
        var response = controller.report(JOURNEY.toString(), REF.toString(), request(ACTOR, body));
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isEqualTo(new BrowserCommunityTrafficV3Controller.ReportResponse(
                "received", received, received.plusSeconds(168L * 3600)));
        verify(store).report(ACTOR, JOURNEY, REF, REQUEST, "INACCURATE");
        String extra = body.replace("}", ",\"anchor\":\"" + UUID.randomUUID() + "\"}");
        assertThatThrownBy(() -> controller.report(JOURNEY.toString(), REF.toString(),
                request(ACTOR, extra))).isInstanceOf(IllegalArgumentException.class);
        String duplicate = body.replace("}", ",\"reason\":\"SPAM\"}");
        assertThatThrownBy(() -> controller.report(JOURNEY.toString(), REF.toString(),
                request(ACTOR, duplicate))).isInstanceOf(IllegalArgumentException.class);
        verifyNoMoreInteractions(store);
    }

    private BrowserCommunityTrafficV3Controller controller() {
        return new BrowserCommunityTrafficV3Controller(store, sessions, policy, rates);
    }

    private static MockHttpServletRequest request(UUID actor, String body) {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Routiqo-Account", actor.toString());
        request.setContentType("application/json");
        if (body != null) request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
