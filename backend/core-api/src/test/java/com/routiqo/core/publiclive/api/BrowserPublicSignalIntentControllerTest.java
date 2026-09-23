package com.routiqo.core.publiclive.api;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.publiclive.application.PublicSignalIntentService;
import com.routiqo.core.publiclive.application.PublicSignalIntentStore;
import com.routiqo.core.security.BrowserAuthPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BrowserPublicSignalIntentControllerTest {
    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID JOURNEY = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID COMMAND = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID REQUEST = UUID.fromString("10000000-0000-4000-8000-000000000004");
    private final PublicSignalIntentService intents = mock(PublicSignalIntentService.class);
    private final GoogleSessionService sessions = mock(GoogleSessionService.class);
    private final AuthRateGate rates = mock(AuthRateGate.class);
    private final BrowserAuthPolicy policy = mock(BrowserAuthPolicy.class);

    @Test void shareBindsAuthenticatedActorPathAndExplicitRequest() {
        when(sessions.authenticate(any())).thenReturn(ACTOR);
        when(rates.allow(ACTOR.toString(), "public-signal-share-request", 12)).thenReturn(true);
        when(intents.share(ACTOR, JOURNEY, COMMAND, REQUEST)).thenReturn(
                new PublicSignalIntentStore.Intent(ACTOR, COMMAND, JOURNEY, UUID.randomUUID(),
                        1, 0, UUID.randomUUID(), "TRAFFIC", "TRAFFIC_SLOW",
                        Instant.parse("2026-09-23T10:00:00Z"),
                        Instant.parse("2026-09-23T10:01:00Z"),
                        Instant.parse("2026-09-23T10:15:00Z"),
                        Instant.parse("2026-09-23T10:02:00Z"), REQUEST,
                        PublicSignalIntentStore.State.ACTIVE));
        var controller = new BrowserPublicSignalIntentController(intents, sessions, policy, rates, true);
        var result = controller.share(JOURNEY.toString(), COMMAND.toString(), request(ACTOR,
                "{\"requestId\":\"" + REQUEST + "\",\"purpose\":\"public-live-moment-v1\"}"));
        assertThat(result.commandId()).isEqualTo(COMMAND);
        assertThat(result.status()).isEqualTo("shared");
        verify(intents).share(ACTOR, JOURNEY, COMMAND, REQUEST);
    }

    @Test void stopSurvivesShareDisableAndDoesNotReadJourney() {
        when(sessions.authenticate(any())).thenReturn(ACTOR);
        when(rates.allow(ACTOR.toString(), "public-signal-stop-request", 30)).thenReturn(true);
        when(intents.stop(ACTOR, JOURNEY, COMMAND)).thenReturn(PublicSignalIntentStore.State.STOPPED);
        var controller = new BrowserPublicSignalIntentController(intents, sessions, policy, rates, false);
        assertThat(controller.stop(JOURNEY.toString(), COMMAND.toString(), request(ACTOR, "{}"))
                .status()).isEqualTo("stopped");
        assertThatThrownBy(() -> controller.share(JOURNEY.toString(), COMMAND.toString(),
                request(ACTOR, "{}"))).isInstanceOf(RuntimeException.class);
        verify(intents).stop(ACTOR, JOURNEY, COMMAND);
        verify(intents, never()).share(any(), any(), any(), any());
    }

    @Test void accountSwitchAndMalformedBodyNeverReachIntentService() {
        when(sessions.authenticate(any())).thenReturn(ACTOR);
        var controller = new BrowserPublicSignalIntentController(intents, sessions, policy, rates, true);
        assertThatThrownBy(() -> controller.stop(JOURNEY.toString(), COMMAND.toString(),
                request(UUID.randomUUID(), "{}"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> controller.share(JOURNEY.toString(), COMMAND.toString(),
                request(ACTOR, "{}"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(intents);
    }

    private static MockHttpServletRequest request(UUID actor, String body) {
        var request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.addHeader("X-Routiqo-Account", actor.toString());
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
