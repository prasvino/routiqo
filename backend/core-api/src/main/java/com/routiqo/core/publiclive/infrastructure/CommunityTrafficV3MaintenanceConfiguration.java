package com.routiqo.core.publiclive.infrastructure;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

/** Independent switch: disable public V3 while retention continues after staging rollback. */
@Configuration(proxyBeanMethods = false)
@Profile("persistence")
@ConditionalOnProperty(name = "ROUTIQO_COMMUNITY_TRAFFIC_V3_MAINTENANCE_ENABLED", havingValue = "true")
@EnableScheduling
public class CommunityTrafficV3MaintenanceConfiguration {
    @Bean ThreadPoolTaskScheduler communityTrafficV3MaintenanceScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("community-traffic-retention-");
        return scheduler;
    }

    @Bean JdbcCommunityTrafficV3Cleanup communityTrafficV3Cleanup(JdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        return new JdbcCommunityTrafficV3Cleanup(jdbc, manager, Clock.systemUTC());
    }

    @Bean CommunityTrafficV3MaintenanceJob communityTrafficV3MaintenanceJob(
            JdbcCommunityTrafficV3Cleanup cleanup) {
        return new CommunityTrafficV3MaintenanceJob(cleanup);
    }
}
