package com.routiqo.core.publiclive.api;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.publiclive.application.PublicSignalIntentService;
import com.routiqo.core.publiclive.application.PublicSignalIntentStore;
import com.routiqo.core.security.BrowserAuthPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BrowserPublicSignalIntentListControllerTest {
    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID JOURNEY = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID COMMAND = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final Instant SHARED = Instant.parse("2026-09-23T10:02:00Z");
    private final PublicSignalIntentService intents = mock(PublicSignalIntentService.class);
    private final GoogleSessionService sessions = mock(GoogleSessionService.class);
    private final AuthRateGate rates = mock(AuthRateGate.class);
    private final BrowserPublicSignalIntentListController controller =
            new BrowserPublicSignalIntentListController(intents, sessions,
                    mock(BrowserAuthPolicy.class), rates);

    @Test void returnsOnlyPrivateStopHandlesAndRoundTripsBoundedCursor() {
        when(sessions.authenticate(any())).thenReturn(ACTOR);
        when(rates.allow(ACTOR.toString(), "public-signal-intent-list-request", 30))
                .thenReturn(true);
        var cursor = new PublicSignalIntentStore.Cursor(SHARED, COMMAND);
        when(intents.list(ACTOR, null)).thenReturn(new PublicSignalIntentStore.HandlePage(
                List.of(new PublicSignalIntentStore.Handle(JOURNEY, COMMAND,
                        PublicSignalIntentStore.State.ACTIVE, SHARED)), cursor));
        var page = controller.list(request(ACTOR, null));
        assertThat(page.intents()).containsExactly(new BrowserPublicSignalIntentListController.Handle(
                JOURNEY, COMMAND, "shared", SHARED));
        assertThat(page.nextCursor()).matches("[A-Za-z0-9_-]+");
        when(intents.list(ACTOR, cursor)).thenReturn(new PublicSignalIntentStore.HandlePage(
                List.of(new PublicSignalIntentStore.Handle(JOURNEY, COMMAND,
                        PublicSignalIntentStore.State.STOPPED, SHARED)), null));
        var next = controller.list(request(ACTOR, "cursor=" + page.nextCursor()));
        assertThat(next.intents().getFirst().status()).isEqualTo("stopped");
        assertThat(next.nextCursor()).isNull();
        verify(intents).list(ACTOR, cursor);
        assertThat(page.toString()).doesNotContain(ACTOR.toString(), COMMAND.toString());
    }

    @Test void accountSwitchMalformedCursorAndExtraQueryFailClosed() {
        when(sessions.authenticate(any())).thenReturn(ACTOR);
        assertThatThrownBy(() -> controller.list(request(UUID.randomUUID(), null)))
                .isInstanceOf(SecurityException.class);
        for (String query : new String[] {"cursor=%%%", "cursor=YQ", "cursor=a&actor=" + ACTOR,
                "actor=" + ACTOR}) {
            assertThatThrownBy(() -> controller.list(request(ACTOR, query)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(intents);
    }

    private static MockHttpServletRequest request(UUID actor, String query) {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Routiqo-Account", actor.toString());
        request.setQueryString(query);
        return request;
    }
}
