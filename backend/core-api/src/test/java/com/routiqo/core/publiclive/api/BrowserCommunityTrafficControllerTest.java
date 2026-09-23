package com.routiqo.core.publiclive.api;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.publiclive.application.CommunityTrafficCandidateStore;
import com.routiqo.core.publiclive.application.CommunityTrafficShareService;
import com.routiqo.core.security.BrowserAuthPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BrowserCommunityTrafficControllerTest {
    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID JOURNEY = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID COMMAND = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID REQUEST = UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final Instant WINDOW = Instant.parse("2026-09-23T10:00:00Z");
    private final CommunityTrafficShareService service = mock(CommunityTrafficShareService.class);
    private final GoogleSessionService sessions = mock(GoogleSessionService.class);
    private final AuthRateGate rates = mock(AuthRateGate.class);
    private final BrowserAuthPolicy policy = mock(BrowserAuthPolicy.class);
    private final BrowserCommunityTrafficController controller =
            new BrowserCommunityTrafficController(service, sessions, policy, rates);

    @Test void explicitPurposeBindsOwnerAndRecoveryReturnsOnlyServiceHandles() {
        when(sessions.authenticate(any())).thenReturn(ACTOR);
        when(rates.allow(ACTOR.toString(), "community-traffic-v3-share", 12)).thenReturn(true);
        when(rates.allow(ACTOR.toString(), "community-traffic-v3-recover", 30)).thenReturn(true);
        when(service.share(ACTOR, JOURNEY, COMMAND, REQUEST)).thenReturn(candidate());
        when(service.recover(ACTOR)).thenReturn(List.of(candidate()));
        var shared = controller.share(JOURNEY.toString(), COMMAND.toString(), request(ACTOR,
                "{\"requestId\":\"" + REQUEST + "\",\"purpose\":\"community-traffic-v3\"}"));
        assertThat(shared.status()).isEqualTo("accepted_for_consideration");
        assertThat(shared.windowEndsAt()).isEqualTo(WINDOW.plusSeconds(300));
        assertThat(controller.recover(request(ACTOR, "")).getFirst().commandId()).isEqualTo(COMMAND);
        verify(service).share(ACTOR, JOURNEY, COMMAND, REQUEST);
    }

    @Test void accountSwitchAndOldPurposeNeverReachService() {
        when(sessions.authenticate(any())).thenReturn(ACTOR);
        assertThatThrownBy(() -> controller.share(JOURNEY.toString(), COMMAND.toString(),
                request(UUID.randomUUID(), "{}"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> controller.share(JOURNEY.toString(), COMMAND.toString(),
                request(ACTOR, "{\"requestId\":\"" + REQUEST
                        + "\",\"purpose\":\"public-live-moment-v1\"}")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
    }

    private static CommunityTrafficCandidateStore.Candidate candidate() {
        return new CommunityTrafficCandidateStore.Candidate(UUID.randomUUID(), ACTOR,
                JOURNEY, COMMAND, REQUEST, UUID.randomUUID(), "TRAFFIC_SLOW", WINDOW,
                UUID.randomUUID(), 7, WINDOW.plusSeconds(20), WINDOW.plusSeconds(30),
                WINDOW.plusSeconds(300 + 24 * 3600), CommunityTrafficCandidateStore.State.ACTIVE);
    }

    private static MockHttpServletRequest request(UUID actor, String body) {
        var request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.addHeader("X-Routiqo-Account", actor.toString());
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
