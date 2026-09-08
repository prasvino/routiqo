package com.routiqo.core.journey.infrastructure;

import com.routiqo.core.journey.application.JourneyService;
import com.routiqo.core.journey.application.JourneyStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("persistence")
public class JourneyPersistenceConfiguration {
    @Bean
    JourneyStore journeyStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcJourneyStore(jdbc, manager);
    }
    @Bean
    JourneyService journeyService(JourneyStore store) {
        return new JourneyService(store, Clock.systemUTC());
    }
}
