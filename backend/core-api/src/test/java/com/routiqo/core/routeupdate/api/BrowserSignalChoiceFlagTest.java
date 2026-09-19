package com.routiqo.core.routeupdate.api;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.routeupdate.application.CatalogSignalService;
import com.routiqo.core.routeupdate.application.PrivateAnchorChoiceService;
import com.routiqo.core.security.BrowserAuthPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BrowserSignalChoiceFlagTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BrowserSignalChoiceController.class, Dependencies.class)
            .withPropertyValues("spring.profiles.active=web-auth,routing,persistence");

    @Test void controllerRequiresBothSignalAndChoiceFlags() {
        for (String[] properties : new String[][] {
                {},
                {"ROUTIQO_LIVE_SIGNAL_API_ENABLED=true"},
                {"ROUTIQO_LIVE_CHOICE_API_ENABLED=true"},
                {"ROUTIQO_LIVE_SIGNAL_API_ENABLED=false",
                        "ROUTIQO_LIVE_CHOICE_API_ENABLED=true"},
                {"ROUTIQO_LIVE_SIGNAL_API_ENABLED=true",
                        "ROUTIQO_LIVE_CHOICE_API_ENABLED=false"}}) {
            runner.withPropertyValues(properties).run(context -> assertThat(context)
                    .hasNotFailed().doesNotHaveBean(BrowserSignalChoiceController.class));
        }
        runner.withPropertyValues("ROUTIQO_LIVE_SIGNAL_API_ENABLED=true",
                        "ROUTIQO_LIVE_CHOICE_API_ENABLED=true")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(BrowserSignalChoiceController.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean PrivateAnchorChoiceService choices() { return mock(PrivateAnchorChoiceService.class); }
        @Bean CatalogSignalService signals() { return mock(CatalogSignalService.class); }
        @Bean GoogleSessionService sessions() { return mock(GoogleSessionService.class); }
        @Bean BrowserAuthPolicy policy() { return mock(BrowserAuthPolicy.class); }
        @Bean AuthRateGate rates() { return mock(AuthRateGate.class); }
    }
}
