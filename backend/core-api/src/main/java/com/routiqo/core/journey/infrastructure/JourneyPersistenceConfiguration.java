package com.routiqo.core.journey.infrastructure;

import com.routiqo.core.journey.application.JourneyService;
import com.routiqo.core.journey.application.JourneyStore;
import com.routiqo.core.identity.application.AccountWriteAuthority;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("persistence")
public class JourneyPersistenceConfiguration {
    @Bean
    JdbcJourneyStore journeyStore(JdbcTemplate jdbc, AccountWriteAuthority accounts) {
        return new JdbcJourneyStore(jdbc, accounts);
    }
    @Bean
    JourneyService journeyService(JourneyStore store) {
        return new JourneyService(store, Clock.systemUTC());
    }
}
