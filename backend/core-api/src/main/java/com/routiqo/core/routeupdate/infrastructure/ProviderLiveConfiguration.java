package com.routiqo.core.routeupdate.infrastructure;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("web-auth & persistence")
@ConditionalOnProperty(name = "ROUTIQO_PROVIDER_LIVE_ENABLED", havingValue = "true")
public class ProviderLiveConfiguration {
    @Bean
    NdmaCapAlerts ndmaCapAlerts() {
        return new NdmaCapAlerts(NdmaCapAlerts.httpFetcher(), Clock.systemUTC());
    }
}
