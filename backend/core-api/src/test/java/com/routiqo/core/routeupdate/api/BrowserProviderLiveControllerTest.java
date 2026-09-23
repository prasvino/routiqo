package com.routiqo.core.routeupdate.api;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.journey.application.JourneyNotFound;
import com.routiqo.core.journey.application.JourneyService;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.routeupdate.infrastructure.NdmaCapAlerts;
import com.routiqo.core.security.BrowserAuthPolicy;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class BrowserProviderLiveControllerTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID JOURNEY = UUID.fromString("00000000-0000-4000-8000-000000000002");

    private final JourneyService journeys = mock(JourneyService.class);
    private final GoogleSessionService sessions = mock(GoogleSessionService.class);
    private final AuthRateGate rates = mock(AuthRateGate.class);
    private final NdmaCapAlerts alerts = mock(NdmaCapAlerts.class);
    private final BrowserProviderLiveController controller = new BrowserProviderLiveController(
            journeys, sessions, new BrowserAuthPolicy("http://localhost:3000", false), rates, alerts);

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/api/v1/journeys/" + JOURNEY + "/provider-alerts");
        request.setCookies(new Cookie("routiqo_session", "opaque"));
        request.addHeader("X-Routiqo-Account", ACTOR.toString());
        when(sessions.authenticate("opaque")).thenReturn(ACTOR);
        return request;
    }

    @Test void requiresExactAccountAndActiveOwnershipBeforeProviderFetch() {
        MockHttpServletRequest wrongAccount = request();
        wrongAccount.removeHeader("X-Routiqo-Account");
        wrongAccount.addHeader("X-Routiqo-Account", UUID.randomUUID().toString());
        assertThatThrownBy(() -> controller.read(JOURNEY.toString(), wrongAccount))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(journeys, alerts);

        when(journeys.get(ACTOR, JOURNEY)).thenThrow(new JourneyNotFound());
        assertThatThrownBy(() -> controller.read(JOURNEY.toString(), request()))
                .isInstanceOf(JourneyNotFound.class);
        verifyNoInteractions(alerts);
    }

    @Test void rechecksActiveJourneyAfterProviderReadAndRateLimits() {
        when(journeys.get(ACTOR, JOURNEY)).thenReturn(Journey.start(JOURNEY, ACTOR,
                Journey.Kind.TRIP, Instant.parse("2026-09-23T08:00:00Z")));
        when(rates.allow(ACTOR.toString(), "provider-live-read-account", 5)).thenReturn(false);
        assertThat(controller.read(JOURNEY.toString(), request()).getStatusCode().value()).isEqualTo(429);
        verifyNoInteractions(alerts);

        when(rates.allow(ACTOR.toString(), "provider-live-read-account", 5)).thenReturn(true);
        when(alerts.current()).thenReturn(List.of());
        when(journeys.get(ACTOR, JOURNEY)).thenReturn(
                Journey.start(JOURNEY, ACTOR, Journey.Kind.TRIP,
                        Instant.parse("2026-09-23T08:00:00Z")),
                Journey.start(JOURNEY, ACTOR, Journey.Kind.TRIP,
                        Instant.parse("2026-09-23T08:00:00Z")).complete(ACTOR,
                                Instant.parse("2026-09-23T09:00:00Z")));
        assertThatThrownBy(() -> controller.read(JOURNEY.toString(), request()))
                .isInstanceOf(JourneyNotFound.class);
        verify(alerts).current();
    }

    @Test void returnsOnlyCurrentOfficialRowsWithNoStore() {
        when(journeys.get(ACTOR, JOURNEY)).thenReturn(Journey.start(JOURNEY, ACTOR,
                Journey.Kind.COMMUTE, Instant.parse("2026-09-23T08:00:00Z")));
        when(rates.allow(ACTOR.toString(), "provider-live-read-account", 5)).thenReturn(true);
        when(alerts.current()).thenReturn(List.of());
        var result = controller.read(JOURNEY.toString(), request());
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(result.getBody().source()).isEqualTo("NDMA SACHET");
        verify(journeys, times(2)).get(ACTOR, JOURNEY);
    }
}
