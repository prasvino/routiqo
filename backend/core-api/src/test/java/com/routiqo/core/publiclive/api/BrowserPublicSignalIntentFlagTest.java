package com.routiqo.core.publiclive.api;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.publiclive.application.PublicSignalIntentService;
import com.routiqo.core.security.BrowserAuthPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BrowserPublicSignalIntentFlagTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BrowserPublicSignalIntentController.class, Dependencies.class)
            .withPropertyValues("spring.profiles.active=web-auth,routing,persistence");

    @Test void transportNeedsItsOwnFlagAndCuratedAnchorAuthority() {
        runner.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(BrowserPublicSignalIntentController.class));
        runner.withPropertyValues("ROUTIQO_PUBLIC_SIGNAL_INTENT_API_ENABLED=true")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(BrowserPublicSignalIntentController.class));
        runner.withPropertyValues("ROUTIQO_PUBLIC_SIGNAL_INTENT_API_ENABLED=true",
                        "ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED=true",
                        "ROUTIQO_PUBLIC_SIGNAL_INTENT_SHARE_ENABLED=false",
                        "ROUTIQO_LIVE_SIGNAL_API_ENABLED=false")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(BrowserPublicSignalIntentController.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean PublicSignalIntentService intents() { return mock(PublicSignalIntentService.class); }
        @Bean GoogleSessionService sessions() { return mock(GoogleSessionService.class); }
        @Bean BrowserAuthPolicy policy() { return mock(BrowserAuthPolicy.class); }
        @Bean AuthRateGate rates() { return mock(AuthRateGate.class); }
    }
}
