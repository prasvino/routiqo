package com.routiqo.core.planning.infrastructure;

import com.routiqo.core.planning.application.PlanningBackupService;
import com.routiqo.core.planning.application.PlanningCopyStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("persistence")
public class PlanningPersistenceConfiguration {
    @Bean PlanningCopyStore planningCopyStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcPlanningCopyStore(jdbc, manager);
    }
    @Bean PlanningBackupService planningBackupService(PlanningCopyStore store) {
        return new PlanningBackupService(store, Clock.systemUTC());
    }
}
