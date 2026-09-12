package com.routiqo.core.journal.infrastructure;

import com.routiqo.core.journal.application.JournalService;
import com.routiqo.core.journal.application.JournalStore;
import com.routiqo.core.journey.application.JourneyService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("persistence")
public class JournalPersistenceConfiguration {
    @Bean JournalStore journalStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcJournalStore(jdbc, manager);
    }
    @Bean JournalService journalService(JourneyService journeys, JournalStore store) {
        return new JournalService(journeys, store, Clock.systemUTC());
    }
}
