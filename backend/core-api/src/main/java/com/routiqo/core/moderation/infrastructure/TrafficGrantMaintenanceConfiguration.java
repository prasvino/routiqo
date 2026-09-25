package com.routiqo.core.moderation.infrastructure;

import com.routiqo.core.security.ConditionalOnExactlyTrue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("persistence")
@ConditionalOnExactlyTrue({"ROUTIQO_V3_GRANT_ADMIN_MAINTENANCE_ENABLED"})
@EnableScheduling
public class TrafficGrantMaintenanceConfiguration {
    @Bean JdbcTrafficGrantCleanup trafficGrantCleanup(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcTrafficGrantCleanup(jdbc, manager);
    }
    @Bean TrafficGrantMaintenanceJob trafficGrantMaintenanceJob(JdbcTrafficGrantCleanup cleanup) {
        return new TrafficGrantMaintenanceJob(cleanup);
    }
}
