package com.routiqo.core.publiclive.api;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.publiclive.application.CommunityTrafficShareService;
import com.routiqo.core.security.BrowserAuthPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BrowserCommunityTrafficFlagTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BrowserCommunityTrafficController.class, Dependencies.class)
            .withPropertyValues("spring.profiles.active=web-auth,routing,persistence");

    @Test void transportRequiresIndependentV3FlagAndAnchorAuthority() {
        runner.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(BrowserCommunityTrafficController.class));
        runner.withPropertyValues("ROUTIQO_COMMUNITY_TRAFFIC_V3_ENABLED=true")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(BrowserCommunityTrafficController.class));
        runner.withPropertyValues("ROUTIQO_COMMUNITY_TRAFFIC_V3_ENABLED=true",
                        "ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED=true")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(BrowserCommunityTrafficController.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean CommunityTrafficShareService service() { return mock(CommunityTrafficShareService.class); }
        @Bean GoogleSessionService sessions() { return mock(GoogleSessionService.class); }
        @Bean BrowserAuthPolicy policy() { return mock(BrowserAuthPolicy.class); }
        @Bean AuthRateGate rates() { return mock(AuthRateGate.class); }
    }
}
