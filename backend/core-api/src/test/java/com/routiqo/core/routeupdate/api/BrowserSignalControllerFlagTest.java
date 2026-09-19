package com.routiqo.core.routeupdate.api;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.application.GoogleSessionService;
import com.routiqo.core.routeupdate.application.CatalogSignalService;
import com.routiqo.core.security.BrowserAuthPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BrowserSignalControllerFlagTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BrowserSignalController.class, Dependencies.class)
            .withPropertyValues("spring.profiles.active=web-auth,routing,persistence");

    @Test void controllerAndStopRequireOnlyTheSignalFlag() {
        runner.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(BrowserSignalController.class));
        runner.withPropertyValues("ROUTIQO_LIVE_SIGNAL_API_ENABLED=false",
                        "ROUTIQO_LIVE_CHOICE_API_ENABLED=true")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(BrowserSignalController.class));
        runner.withPropertyValues("ROUTIQO_LIVE_SIGNAL_API_ENABLED=true",
                        "ROUTIQO_LIVE_CHOICE_API_ENABLED=false")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(BrowserSignalController.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean CatalogSignalService signals() { return mock(CatalogSignalService.class); }
        @Bean GoogleSessionService sessions() { return mock(GoogleSessionService.class); }
        @Bean BrowserAuthPolicy policy() { return mock(BrowserAuthPolicy.class); }
        @Bean AuthRateGate rates() { return mock(AuthRateGate.class); }
    }
}
